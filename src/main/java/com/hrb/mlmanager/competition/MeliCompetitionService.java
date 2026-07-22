package com.hrb.mlmanager.competition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hrb.mlmanager.meli.MeliClient;
import com.hrb.mlmanager.meli.MeliClient.MeliResponse;
import com.hrb.mlmanager.perf.PerfSnapshot;
import com.hrb.mlmanager.perf.PerfSnapshotRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Análise de concorrência dos anúncios. Fase 1: detalhe ao vivo por item para
 * anúncios de <b>catálogo</b> (têm {@code catalog_product_id}) — compara as
 * ofertas de {@code /products/{cpid}/items}, identifica o ganhador do buy box,
 * calcula minha posição, o gap de preço e o preço para ganhar.
 *
 * Reusa {@link MeliClient} (mesmos endpoints de catálogo já usados no clone).
 * Anúncios avulsos (busca pública) e descoberta de mercado ficam pras fases 3/4.
 */
@Service
public class MeliCompetitionService {

    private static final Logger log = LoggerFactory.getLogger(MeliCompetitionService.class);
    static final ObjectMapper M = new ObjectMapper();
    static final String KIND = "competition";

    private static final List<String> COMPETITION_FIELDS = List.of(
            "id", "title", "price", "status", "thumbnail", "permalink",
            "category_id", "catalog_product_id", "seller_custom_field");

    private final MeliClient client;
    private final PerfSnapshotRepository repo;

    public MeliCompetitionService(MeliClient client, PerfSnapshotRepository repo) {
        this.client = client;
        this.repo = repo;
    }

    /** Detalhe ao vivo por item. Decide catálogo vs. avulso por catalog_product_id. */
    public ObjectNode analyzeItem(long userId, String itemId) {
        MeliResponse itemResp = client.get("/items/" + itemId, Map.of("include_attributes", "all"), userId);
        if (itemResp.status() == 404 || itemResp.data() == null || !itemResp.data().isObject()) {
            ObjectNode out = M.createObjectNode();
            out.put("item_id", itemId);
            out.put("mode", "not_found");
            out.put("message", "Anúncio não encontrado.");
            return out;
        }
        JsonNode item = itemResp.data();
        String cpid = item.path("catalog_product_id").asText(null);
        double myPrice = item.path("price").asDouble(0);

        if (cpid == null || cpid.isBlank()) {
            // Fase 3 cobre avulsos por busca pública. Por enquanto, resposta explícita.
            ObjectNode out = M.createObjectNode();
            out.put("item_id", itemId);
            out.put("mode", "standalone");
            out.put("title", item.path("title").asText(""));
            out.put("category_id", item.path("category_id").asText(""));
            out.put("my_price", myPrice);
            out.put("message", "Anúncio fora de catálogo — comparação por busca pública ainda não disponível.");
            return out;
        }

        ObjectNode analysis = fetchCatalogAnalysis(userId, cpid, itemId, myPrice);
        if (analysis == null) {
            log.warn("competition: /products/{}/items user={} sem ofertas", cpid, userId);
            ObjectNode out = M.createObjectNode();
            out.put("item_id", itemId);
            out.put("mode", "catalog");
            out.put("catalog_product_id", cpid);
            out.put("my_price", myPrice);
            out.put("message", "Não foi possível obter as ofertas do produto de catálogo agora.");
            out.set("competitors", M.createArrayNode());
            return out;
        }
        analysis.put("item_id", itemId);
        analysis.put("catalog_product_id", cpid);
        analysis.put("title", item.path("title").asText(""));
        analysis.put("category_id", item.path("category_id").asText(""));
        return analysis;
    }

    /** Busca as ofertas do produto de catálogo e roda o cálculo puro. null = sem dados. */
    private ObjectNode fetchCatalogAnalysis(long userId, String cpid, String itemId, double myPrice) {
        MeliResponse offersResp = client.get("/products/" + cpid + "/items", Map.of("limit", "50"), userId);
        JsonNode offers = offersResp.data() == null ? null : offersResp.data().path("results");
        if (offersResp.status() != 200 || offers == null || !offers.isArray()) return null;
        MeliResponse prod = client.get("/products/" + cpid, userId);
        JsonNode productNode = prod.status() == 200 ? prod.data() : null;
        return analyzeCatalogOffers(productNode, offers, userId, itemId, myPrice);
    }

    // ---------- snapshot por conta (varredura em background) ----------

    @Transactional(readOnly = true)
    public ObjectNode load(long userId) {
        return repo.findByUserIdAndKind(userId, KIND)
                .map(s -> s.getData() instanceof ObjectNode o ? o : null)
                .orElse(null);
    }

    @Transactional
    public void save(ObjectNode snapshot) {
        long userId = snapshot.path("user_id").asLong();
        PerfSnapshot s = repo.findByUserIdAndKind(userId, KIND).orElse(null);
        if (s == null) {
            s = new PerfSnapshot(userId, KIND, Instant.now(), snapshot);
        } else {
            s.setScannedAt(Instant.now());
            s.setData(snapshot);
        }
        repo.save(s);
    }

    /**
     * Varre todos os anúncios da conta e monta uma linha compacta de concorrência
     * por item. Só itens de <b>catálogo</b> recebem checagem de buy box (1 chamada
     * por produto, cacheada); avulsos ficam marcados p/ análise ao vivo (fase 3).
     * Salva checkpoints por lote, como o Quality.
     */
    public ObjectNode build(long userId, String nickname) {
        ObjectNode snapshot = M.createObjectNode();
        snapshot.put("user_id", userId);
        snapshot.put("nickname", nickname);
        snapshot.put("status", "running");
        snapshot.put("started_at", nowIso());
        snapshot.putNull("scanned_at");
        snapshot.put("processed", 0);
        snapshot.put("total", 0);
        snapshot.set("warnings", M.createArrayNode());
        snapshot.set("items", M.createArrayNode());
        snapshot.set("summary", summarize(M.createArrayNode()));
        save(snapshot);

        List<String> ids = client.scanAllItems(userId, null, 100);
        List<JsonNode> raw = new ArrayList<>();
        for (JsonNode it : client.multiGetItems(ids, COMPETITION_FIELDS, userId)) {
            String st = it.path("status").asText("");
            if (!it.path("id").asText("").isEmpty() && (st.equals("active") || st.equals("paused"))) raw.add(it);
        }
        snapshot.put("total", raw.size());
        save(snapshot);

        // Cache por catalog_product_id: itens irmãos do mesmo produto reusam a análise.
        Map<String, ObjectNode> cache = new LinkedHashMap<>();
        ArrayNode items = M.createArrayNode();
        int batch = 0;
        for (JsonNode it : raw) {
            items.add(competitionRow(userId, it, cache));
            if (++batch % 50 == 0) {
                snapshot.put("processed", batch);
                save(snapshot);
            }
        }
        snapshot.put("processed", raw.size());
        snapshot.put("status", "complete");
        snapshot.put("scanned_at", nowIso());
        snapshot.set("items", items);
        snapshot.set("summary", summarize(items));
        save(snapshot);
        log.info("competition: snapshot concluido user={} itens={}", userId, raw.size());
        return snapshot;
    }

    /** Linha compacta por item (sem a lista completa de concorrentes, que é on-demand). */
    private ObjectNode competitionRow(long userId, JsonNode item, Map<String, ObjectNode> cache) {
        String itemId = item.path("id").asText("");
        String cpid = item.path("catalog_product_id").asText(null);
        double myPrice = item.path("price").asDouble(0);

        ObjectNode row = M.createObjectNode();
        row.put("id", itemId);
        row.put("title", item.path("title").asText(""));
        row.put("sku", item.path("seller_custom_field").asText(""));
        row.set("thumbnail", nullable(item.get("thumbnail")));
        row.set("permalink", nullable(item.get("permalink")));
        row.set("status", nullable(item.get("status")));
        row.put("price", myPrice);
        row.put("category_id", item.path("category_id").asText(""));

        if (cpid == null || cpid.isBlank()) {
            row.put("mode", "standalone");
            row.put("comp_status", "needs_live_check");
            return row;
        }

        // 1 análise por produto de catálogo; itens do mesmo produto compartilham,
        // mas posição/gap dependem do MEU preço → recalcula sobre as ofertas cacheadas.
        ObjectNode analysis = cache.computeIfAbsent(cpid, k -> {
            ObjectNode a = fetchCatalogAnalysis(userId, cpid, itemId, myPrice);
            return a == null ? M.createObjectNode().put("_failed", true) : a;
        });
        row.put("mode", "catalog");
        row.put("catalog_product_id", cpid);
        if (analysis.path("_failed").asBoolean(false)) {
            row.put("comp_status", "unknown");
            return row;
        }
        row.put("comp_status", analysis.path("status").asText("competing"));
        row.put("competitor_count", analysis.path("competitor_count").asInt(0));
        row.put("my_position", analysis.path("my_position").asInt(0));
        row.set("winner_price", analysis.get("winner_price"));
        row.set("price_gap", analysis.get("price_gap"));
        row.set("price_gap_pct", analysis.get("price_gap_pct"));
        row.set("price_to_win", analysis.get("price_to_win"));
        return row;
    }

    static ObjectNode summarize(JsonNode items) {
        int analyzed = 0, catalog = 0, standalone = 0;
        int winning = 0, sharing = 0, competing = 0, notListed = 0, unknown = 0;
        for (JsonNode it : items) {
            analyzed++;
            if ("standalone".equals(it.path("mode").asText())) { standalone++; continue; }
            catalog++;
            switch (it.path("comp_status").asText("")) {
                case "winning" -> winning++;
                case "sharing" -> sharing++;
                case "competing" -> competing++;
                case "not_listed" -> notListed++;
                default -> unknown++;
            }
        }
        ObjectNode out = M.createObjectNode();
        out.put("analyzed", analyzed);
        out.put("catalog", catalog);
        out.put("standalone", standalone);
        out.put("winning", winning);
        out.put("sharing", sharing);
        out.put("competing", competing);   // perdendo o buy box
        out.put("not_listed", notListed);
        out.put("unknown", unknown);
        return out;
    }

    private static JsonNode nullable(JsonNode node) {
        return node == null || node.isNull() ? M.nullNode() : node;
    }

    private static String nowIso() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /**
     * Núcleo puro (sem rede) do cálculo de posição/preço — testável isolado.
     * {@code productNode} pode ser null (usa a oferta mais barata como ganhador).
     */
    static ObjectNode analyzeCatalogOffers(JsonNode productNode, JsonNode offers,
                                           long userId, String myItemId, double myPrice) {
        List<ObjectNode> competitors = new ArrayList<>();
        ObjectNode mine = null;
        for (JsonNode offer : offers) {
            ObjectNode row = offerRow(offer);
            long sellerId = offer.path("seller_id").asLong(0);
            String offerItemId = row.path("item_id").asText("");
            boolean isMine = sellerId == userId || (!offerItemId.isEmpty() && offerItemId.equals(myItemId));
            row.put("is_mine", isMine);
            if (isMine && mine == null) {
                mine = row;
                if (myPrice <= 0) myPrice = row.path("price").asDouble(0);
            }
            competitors.add(row);
        }
        // Ordena por preço asc (ofertas sem preço vão pro fim).
        competitors.sort(Comparator.comparingDouble(r -> priceOrMax(r)));

        // Ganhador do buy box: preferir o declarado pelo produto; senão a oferta mais barata "ativa".
        String winnerItemId = productNode == null ? null : productNode.path("buy_box_winner").path("item_id").asText(null);
        double winnerPrice = productNode == null ? 0 : productNode.path("buy_box_winner").path("price").asDouble(0);
        ObjectNode winner = null;
        if (winnerItemId != null && !winnerItemId.isBlank()) {
            for (ObjectNode r : competitors) {
                if (winnerItemId.equals(r.path("item_id").asText(""))) { winner = r; break; }
            }
        }
        if (winner == null && !competitors.isEmpty()) {
            winner = competitors.get(0); // mais barato após ordenação
        }
        if (winner != null) {
            if (winnerPrice <= 0) winnerPrice = winner.path("price").asDouble(0);
            winner.put("is_winner", true);
        }

        int myPosition = 0;
        for (int i = 0; i < competitors.size(); i++) {
            if (competitors.get(i) == mine) { myPosition = i + 1; break; }
        }

        boolean iAmWinner = mine != null && winner == mine;
        Double priceGap = null;      // meu preço - preço do ganhador (absoluto)
        Double priceGapPct = null;   // em % sobre o preço do ganhador
        Double priceToWin = null;    // preço para assumir a liderança
        if (winnerPrice > 0 && myPrice > 0 && !iAmWinner) {
            priceGap = round2(myPrice - winnerPrice);
            priceGapPct = round2((myPrice - winnerPrice) / winnerPrice * 100.0);
            priceToWin = round2(winnerPrice - 0.01); // logo abaixo do atual ganhador
        }

        String status;
        if (mine == null) status = "not_listed";
        else if (iAmWinner) status = "winning";
        else if (priceGap != null && priceGap <= 0) status = "sharing"; // mesmo preço do topo mas não é o winner
        else status = "competing";

        ArrayNode competitorsArr = M.createArrayNode();
        for (ObjectNode r : competitors) competitorsArr.add(r);

        ObjectNode out = M.createObjectNode();
        out.put("mode", "catalog");
        out.put("status", status);
        out.put("competitor_count", competitors.size());
        out.put("my_price", myPrice > 0 ? round2(myPrice) : 0.0);
        out.put("my_position", myPosition);
        out.put("winner_price", winnerPrice > 0 ? round2(winnerPrice) : 0.0);
        if (priceGap != null) out.put("price_gap", priceGap); else out.putNull("price_gap");
        if (priceGapPct != null) out.put("price_gap_pct", priceGapPct); else out.putNull("price_gap_pct");
        if (priceToWin != null) out.put("price_to_win", priceToWin); else out.putNull("price_to_win");
        out.set("competitors", competitorsArr);
        return out;
    }

    private static ObjectNode offerRow(JsonNode offer) {
        ObjectNode row = M.createObjectNode();
        row.put("item_id", offer.path("item_id").asText(offer.path("id").asText("")));
        row.put("seller_id", offer.path("seller_id").asLong(0));
        row.put("seller_nickname", offer.path("seller").path("nickname").asText(offer.path("seller_nickname").asText("")));
        row.put("price", offer.path("price").asDouble(0));
        if (offer.hasNonNull("original_price")) row.put("original_price", offer.path("original_price").asDouble(0));
        row.put("sold_quantity", offer.path("sold_quantity").asInt(0));
        row.put("available_quantity", offer.path("available_quantity").asInt(0));
        row.put("listing_type_id", offer.path("listing_type_id").asText(""));
        row.put("condition", offer.path("condition").asText(""));
        JsonNode shipping = offer.path("shipping");
        row.put("free_shipping", shipping.path("free_shipping").asBoolean(false));
        row.put("logistic_type", shipping.path("logistic_type").asText(offer.path("logistic_type").asText("")));
        row.put("official_store_id", offer.path("official_store_id").isMissingNode()
                || offer.path("official_store_id").isNull() ? null : offer.path("official_store_id").asText());
        row.put("is_mine", false);
        row.put("is_winner", false);
        return row;
    }

    private static double priceOrMax(JsonNode row) {
        double p = row.path("price").asDouble(0);
        return p > 0 ? p : Double.MAX_VALUE;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
