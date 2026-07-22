package com.hrb.mlmanager.competition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hrb.mlmanager.meli.MeliClient;
import com.hrb.mlmanager.meli.MeliClient.MeliResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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

    private final MeliClient client;

    public MeliCompetitionService(MeliClient client) {
        this.client = client;
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

        MeliResponse prod = client.get("/products/" + cpid, userId);
        MeliResponse offersResp = client.get("/products/" + cpid + "/items", Map.of("limit", "50"), userId);
        JsonNode offers = offersResp.data() == null ? null : offersResp.data().path("results");
        if (offersResp.status() != 200 || offers == null || !offers.isArray()) {
            log.warn("competition: /products/{}/items user={} -> HTTP {}", cpid, userId, offersResp.status());
            ObjectNode out = M.createObjectNode();
            out.put("item_id", itemId);
            out.put("mode", "catalog");
            out.put("catalog_product_id", cpid);
            out.put("my_price", myPrice);
            out.put("message", "Não foi possível obter as ofertas do produto de catálogo agora.");
            out.set("competitors", M.createArrayNode());
            return out;
        }

        JsonNode productNode = prod.status() == 200 ? prod.data() : null;
        ObjectNode analysis = analyzeCatalogOffers(productNode, offers, userId, itemId, myPrice);
        analysis.put("item_id", itemId);
        analysis.put("catalog_product_id", cpid);
        analysis.put("title", firstText(item.path("title").asText(null),
                productNode == null ? null : productNode.path("name").asText(null)));
        analysis.put("category_id", item.path("category_id").asText(""));
        return analysis;
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

    private static String firstText(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return "";
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
