package com.myrias.mlmanager.perf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myrias.mlmanager.meli.MeliClient;
import com.myrias.mlmanager.meli.MeliClient.MeliResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Análise de performance dos anúncios. Espelho de
 * backend/app/services/meli_performance.py.
 *
 * Divergência deliberada do Python: os snapshots (inventário/vendas/ads/visitas),
 * que lá eram arquivos JSON em disco, aqui são linhas em {@link PerfSnapshot}
 * (uma por conta+tipo, corpo JSON em coluna texto). O resto da lógica — scan
 * O(N) só no refresh, visitas/ads sob demanda, janelas de ads ≤90 dias — segue
 * o original. ponytail: corpo do snapshot como JsonNode opaco, igual ao dict do
 * Python; o controller itera por cima.
 */
@Service
public class MeliPerformanceService {

    private static final Logger log = LoggerFactory.getLogger(MeliPerformanceService.class);
    private static final ObjectMapper M = new ObjectMapper();

    static final String INVENTORY = "inventory", SALES = "sales", ADS = "ads", VISITS = "visits";
    private static final int INVENTORY_MAX_AGE_HOURS = 12;
    private static final int MAX_ADS_WINDOW_DAYS = 90;
    private static final int VISITS_MAX_LOOKBACK_DAYS = 725;

    private static final List<String> INVENTORY_FIELDS = List.of(
            "id", "title", "price", "available_quantity", "sold_quantity",
            "status", "permalink", "thumbnail", "category_id",
            "listing_type_id", "seller_custom_field", "attributes", "date_created",
            "original_price", "shipping");

    private static final String ADS_METRICS = String.join(",",
            "clicks", "prints", "cost", "cpc", "acos",
            "direct_amount", "indirect_amount", "total_amount",
            "direct_units_quantity", "indirect_units_quantity", "units_quantity");

    private final MeliClient client;
    private final PerfSnapshotRepository repo;

    public MeliPerformanceService(MeliClient client, PerfSnapshotRepository repo) {
        this.client = client;
        this.repo = repo;
    }

    public record DeleteResult(boolean ok, String action, Integer status, String detail) {}

    // ---------- load / save dos snapshots ----------

    public JsonNode loadInventory(long userId) { return load(userId, INVENTORY); }
    public JsonNode loadSales(long userId)     { return load(userId, SALES); }
    public JsonNode loadAds(long userId)       { return load(userId, ADS); }
    public JsonNode loadVisits(long userId)    { return load(userId, VISITS); }

    private JsonNode load(long userId, String kind) {
        return repo.findByUserIdAndKind(userId, kind).map(PerfSnapshot::getData).orElse(null);
    }

    /** Upsert do snapshot. Single-writer (prod = 1 worker), como os sets em memória do Python. */
    private void save(long userId, String kind, JsonNode body) {
        Instant now = Instant.now();
        PerfSnapshot s = repo.findByUserIdAndKind(userId, kind).orElse(null);
        if (s == null) {
            s = new PerfSnapshot(userId, kind, now, body);
        } else {
            s.setScannedAt(now);
            s.setData(body);
        }
        repo.save(s);
    }

    public Map<String, Object> snapshotStatus(long userId) {
        JsonNode inv = loadInventory(userId), sales = loadSales(userId),
                ads = loadAds(userId), visits = loadVisits(userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inventory_scanned_at", inv == null ? null : txt(inv, "scanned_at"));
        out.put("inventory_count", inv == null ? 0 : inv.path("items").size());
        out.put("sales_scanned_at", sales == null ? null : txt(sales, "scanned_at"));
        out.put("sales_lookback_days", sales == null ? null : (sales.has("lookback_days") ? sales.get("lookback_days").asInt() : null));
        out.put("ads_scanned_at", ads == null ? null : txt(ads, "scanned_at"));
        out.put("visits_scanned_at", visits == null ? null : txt(visits, "scanned_at"));
        out.put("visits_count", visits == null ? 0 : visits.path("by_item").size());
        return out;
    }

    // ---------- deleção de anúncio ----------

    private ObjectNode inventoryRow(JsonNode it) {
        ObjectNode o = M.createObjectNode();
        o.put("id", txt(it, "id"));
        o.put("title", it.path("title").asText(""));
        o.put("sku", extractSku(it));
        o.set("status", it.get("status"));
        o.set("price", it.get("price"));
        o.set("available_quantity", it.get("available_quantity"));
        o.put("sold_quantity", it.path("sold_quantity").asInt(0));
        o.set("listing_type_id", it.get("listing_type_id"));
        o.set("permalink", it.get("permalink"));
        o.set("thumbnail", it.get("thumbnail"));
        o.set("category_id", it.get("category_id"));
        o.set("date_created", it.get("date_created"));
        o.set("original_price", it.get("original_price"));
        JsonNode shipping = it.get("shipping");
        o.set("logistic_type", shipping == null ? null : shipping.get("logistic_type"));
        return o;
    }

    /**
     * Insere/atualiza um item no snapshot após uma criação pelo app. Não altera
     * {@code scanned_at}: o snapshot continua representando a última varredura
     * completa, mas passa a incluir imediatamente o item recém-confirmado.
     */
    // ponytail: sem lock por usuário (como removeItemFromInventory/markClosedInInventory);
    // upgrade pra lock por conta se dois clones simultâneos na mesma conta colidirem.
    public boolean upsertInventoryItem(long userId, JsonNode item) {
        String itemId = txt(item, "id");
        if (itemId == null || itemId.isBlank()) return false;
        JsonNode inv = loadInventory(userId);
        if (inv == null || !inv.has("items")) return false;
        ObjectNode row = inventoryRow(item);
        ArrayNode rebuilt = M.createArrayNode();
        boolean replaced = false;
        for (JsonNode it : inv.get("items")) {
            if (itemId.equals(txt(it, "id"))) {
                rebuilt.add(row);
                replaced = true;
            } else {
                rebuilt.add(it);
            }
        }
        if (!replaced) rebuilt.insert(0, row);
        ((ObjectNode) inv).set("items", rebuilt);
        save(userId, INVENTORY, inv);
        log.info("perf: snapshot atualizado após criação item={} user={}", itemId, userId);
        return true;
    }

    public boolean removeItemFromInventory(long userId, String itemId) {
        JsonNode inv = loadInventory(userId);
        if (inv == null || !inv.has("items")) return false;
        ArrayNode items = (ArrayNode) inv.get("items");
        ArrayNode kept = M.createArrayNode();
        for (JsonNode it : items) {
            if (!itemId.equals(txt(it, "id"))) kept.add(it);
        }
        if (kept.size() != items.size()) {
            ((ObjectNode) inv).set("items", kept);
            save(userId, INVENTORY, inv);
            return true;
        }
        return false;
    }

    private void markClosedInInventory(long userId, String itemId) {
        JsonNode inv = loadInventory(userId);
        if (inv == null || !inv.has("items")) return;
        boolean changed = false;
        for (JsonNode it : inv.get("items")) {
            if (itemId.equals(txt(it, "id"))) {
                ((ObjectNode) it).put("status", "closed");
                changed = true;
            }
        }
        if (changed) save(userId, INVENTORY, inv);
    }

    /**
     * Remove um anúncio: o ML não some com DELETE direto — fecha (status=closed)
     * e depois marca deleted=true. {@code closeOnly} só fecha (reversível).
     * Atualiza o snapshot de inventário no sucesso.
     */
    public DeleteResult deleteListing(long userId, String itemId, boolean closeOnly) {
        MeliResponse rc = client.put("/items/" + itemId, userId, M.createObjectNode().put("status", "closed"));
        Integer cstatus = rc.status();
        if (cstatus != 200 && cstatus != 201) {
            String detail = clip(String.valueOf(rc.data()), 300);
            if (closeOnly) return new DeleteResult(false, "close", cstatus, detail);
            log.warn("perf delete: close falhou item={} status={} {}", itemId, cstatus, clip(detail, 120));
        }

        if (closeOnly) {
            markClosedInInventory(userId, itemId);
            return new DeleteResult(true, "closed", cstatus, "");
        }

        MeliResponse rd = client.put("/items/" + itemId, userId, M.createObjectNode().put("deleted", true));
        Integer dstatus = rd.status();
        if (dstatus == 200 || dstatus == 201) {
            removeItemFromInventory(userId, itemId);
            return new DeleteResult(true, "deleted", dstatus, "");
        }

        // ML recusou deletar (comum com pedidos), mas já fechou acima → reflete closed.
        String detail = clip(String.valueOf(rd.data()), 300);
        if (cstatus == 200 || cstatus == 201) {
            markClosedInInventory(userId, itemId);
            log.info("perf delete: item={} fechado (ML recusou delete: {})", itemId, clip(detail, 120));
            return new DeleteResult(true, "closed", dstatus,
                    "ML não permitiu deletar (provável anúncio com pedidos); ficou fechado.");
        }
        return new DeleteResult(false, "delete", dstatus, detail);
    }

    // ---------- construção dos snapshots ----------

    public JsonNode buildInventory(long userId, String nickname) {
        log.info("perf: iniciando scan de inventário user={}", userId);
        List<String> ids = client.scanAllItems(userId, null, 100);
        log.info("perf: {} ids; buscando detalhes user={}", ids.size(), userId);
        List<JsonNode> raw = client.multiGetItems(ids, INVENTORY_FIELDS, userId);

        ArrayNode items = M.createArrayNode();
        for (JsonNode it : raw) {
            if (txt(it, "id") == null) continue;
            items.add(inventoryRow(it));
        }
        ObjectNode snap = M.createObjectNode();
        snap.put("user_id", userId);
        snap.put("nickname", nickname);
        snap.put("scanned_at", Instant.now().toString());
        snap.set("items", items);
        save(userId, INVENTORY, snap);
        log.info("perf: inventário salvo user={} ({} itens)", userId, items.size());
        return snap;
    }

    @SuppressWarnings("unchecked")
    public JsonNode buildSales(long userId, int lookbackDays, boolean incremental) {
        OffsetDateTime end = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        Map<String, Object> byItem = new LinkedHashMap<>();
        Map<String, Object> bySku = new LinkedHashMap<>();
        OffsetDateTime start = end.minusDays(lookbackDays);

        if (incremental) {
            JsonNode prior = loadSales(userId);
            String priorAt = prior == null ? null : txt(prior, "scanned_at");
            if (prior != null && priorAt != null) {
                byItem = M.convertValue(prior.path("by_item"), Map.class);
                bySku = M.convertValue(prior.path("by_sku"), Map.class);
                if (prior.has("lookback_days")) lookbackDays = prior.get("lookback_days").asInt();
                try {
                    start = Instant.parse(priorAt).atOffset(ZoneOffset.UTC);
                } catch (Exception e) {
                    start = end.minusDays(lookbackDays);
                }
            } else {
                incremental = false;
            }
        }

        int offset = 0;
        final int SAFETY_CAP = 5000;
        while (offset < SAFETY_CAP) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("seller", String.valueOf(userId));
            params.put("order.status", "paid");
            params.put("order.date_created.from", start.toString());
            params.put("order.date_created.to", end.toString());
            params.put("sort", "date_desc");
            params.put("limit", "50");
            params.put("offset", String.valueOf(offset));
            MeliResponse resp;
            try {
                resp = client.get("/orders/search", params, userId);
            } catch (Exception e) {
                log.warn("perf: orders falhou user={}: {}", userId, e.getMessage());
                break;
            }
            JsonNode data = resp.data() == null ? M.createObjectNode() : resp.data();
            JsonNode results = data.path("results");
            if (!results.isArray() || results.isEmpty()) break;
            mergeOrders(results, byItem, bySku);
            int total = data.path("paging").path("total").asInt(0);
            offset += 50;
            if (offset >= total) break;
        }

        ObjectNode snap = M.createObjectNode();
        snap.put("user_id", userId);
        snap.put("scanned_at", Instant.now().toString());
        snap.put("lookback_days", lookbackDays);
        snap.set("by_item", M.valueToTree(byItem));
        snap.set("by_sku", M.valueToTree(bySku));
        save(userId, SALES, snap);
        log.info("perf: vendas salvas user={} modo={} ({} itens, {} skus)",
                userId, incremental ? "incremental" : "full", byItem.size(), bySku.size());
        return snap;
    }

    @SuppressWarnings("unchecked")
    private static void mergeOrders(JsonNode orders, Map<String, Object> byItem, Map<String, Object> bySku) {
        for (JsonNode order : orders) {
            String rawDate = firstNonEmpty(txt(order, "date_closed"), txt(order, "date_created"), "");
            rawDate = rawDate.length() >= 10 ? rawDate.substring(0, 10) : rawDate;
            for (JsonNode oi : order.path("order_items")) {
                JsonNode it = oi.path("item");
                String itemId = it.path("id").asText("");
                int qty = oi.path("quantity").asInt(0);
                double unitPrice = oi.path("unit_price").asDouble(0);
                double revenue = qty * unitPrice;
                String sku = firstNonEmpty(txt(it, "seller_sku"), txt(it, "seller_custom_field"), "").trim();
                double grossPrice = oi.path("gross_price").asDouble(0);
                boolean isPromo = grossPrice > unitPrice + 0.01;

                if (!itemId.isEmpty()) {
                    Map<String, Object> agg = (Map<String, Object>) byItem.computeIfAbsent(itemId, k -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("qty", 0); m.put("revenue", 0.0);
                        m.put("promo_qty", 0); m.put("promo_revenue", 0.0);
                        m.put("last_sale_date", null); m.put("daily", new LinkedHashMap<String, Object>());
                        return m;
                    });
                    agg.put("qty", asInt(agg.get("qty")) + qty);
                    agg.put("revenue", asDbl(agg.get("revenue")) + revenue);
                    if (isPromo) {
                        agg.put("promo_qty", asInt(agg.get("promo_qty")) + qty);
                        agg.put("promo_revenue", asDbl(agg.get("promo_revenue")) + revenue);
                    }
                    String lsd = (String) agg.get("last_sale_date");
                    if (!rawDate.isEmpty() && (lsd == null || rawDate.compareTo(lsd) > 0)) agg.put("last_sale_date", rawDate);
                    if (!rawDate.isEmpty()) {
                        Map<String, Object> daily = (Map<String, Object>) agg.get("daily");
                        Map<String, Object> day = (Map<String, Object>) daily.computeIfAbsent(rawDate, k -> {
                            Map<String, Object> d = new LinkedHashMap<>();
                            d.put("qty", 0); d.put("revenue", 0.0);
                            return d;
                        });
                        day.put("qty", asInt(day.get("qty")) + qty);
                        day.put("revenue", asDbl(day.get("revenue")) + revenue);
                    }
                }
                if (!sku.isEmpty()) {
                    Map<String, Object> sagg = (Map<String, Object>) bySku.computeIfAbsent(sku, k -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("qty", 0); m.put("revenue", 0.0); m.put("last_sale_date", null);
                        return m;
                    });
                    sagg.put("qty", asInt(sagg.get("qty")) + qty);
                    sagg.put("revenue", asDbl(sagg.get("revenue")) + revenue);
                    String lsd = (String) sagg.get("last_sale_date");
                    if (!rawDate.isEmpty() && (lsd == null || rawDate.compareTo(lsd) > 0)) sagg.put("last_sale_date", rawDate);
                }
            }
        }
    }

    public JsonNode buildAds(long userId, int lookbackDays) {
        LocalDate end = LocalDate.now(ZoneOffset.UTC);
        Long advertiserId = advertiserIdFor(userId);

        ObjectNode totals = M.createObjectNode();
        for (String k : List.of("units", "clicks", "prints", "direct_units", "indirect_units")) totals.put(k, 0);
        for (String k : List.of("amount", "cost", "direct_amount", "indirect_amount")) totals.put(k, 0.0);

        if (advertiserId == null) {
            log.info("perf ads: conta {} sem advertiser (sem Product Ads)", userId);
        } else {
            String base = "/advertising/advertisers/" + advertiserId + "/product_ads/campaigns";
            Map<String, String> v2 = Map.of("Api-Version", "2");
            for (String[] win : dateWindows(end, lookbackDays, MAX_ADS_WINDOW_DAYS)) {
                int offset = 0;
                while (true) {
                    Map<String, String> params = new LinkedHashMap<>();
                    params.put("date_from", win[0]); params.put("date_to", win[1]);
                    params.put("metrics", ADS_METRICS); params.put("limit", "100");
                    params.put("offset", String.valueOf(offset));
                    MeliResponse r = client.get(base, params, v2, userId);
                    if (r.status() != 200) {
                        log.warn("perf ads: campaigns status {} user={} janela={}..{} resp={}",
                                r.status(), userId, win[0], win[1], clip(String.valueOf(r.data()), 200));
                        break;
                    }
                    JsonNode data = r.data() == null ? M.createObjectNode() : r.data();
                    JsonNode results = data.path("results");
                    for (JsonNode c : results) {
                        JsonNode m = c.path("metrics");
                        addInt(totals, "units", m.path("units_quantity").asInt(0));
                        addDbl(totals, "amount", m.path("total_amount").asDouble(0));
                        addDbl(totals, "cost", m.path("cost").asDouble(0));
                        addInt(totals, "clicks", m.path("clicks").asInt(0));
                        addInt(totals, "prints", m.path("prints").asInt(0));
                        addDbl(totals, "direct_amount", m.path("direct_amount").asDouble(0));
                        addDbl(totals, "indirect_amount", m.path("indirect_amount").asDouble(0));
                        addInt(totals, "direct_units", m.path("direct_units_quantity").asInt(0));
                        addInt(totals, "indirect_units", m.path("indirect_units_quantity").asInt(0));
                    }
                    int total = data.path("paging").path("total").asInt(0);
                    offset += 100;
                    if (offset >= total || !results.isArray() || results.isEmpty()) break;
                }
            }
            for (String k : List.of("amount", "cost", "direct_amount", "indirect_amount")) {
                totals.put(k, round2(totals.path(k).asDouble(0)));
            }
        }

        ObjectNode snap = M.createObjectNode();
        snap.put("user_id", userId);
        snap.put("scanned_at", Instant.now().toString());
        snap.put("lookback_days", lookbackDays);
        if (advertiserId != null) snap.put("advertiser_id", advertiserId); else snap.putNull("advertiser_id");
        snap.set("totals", totals);
        save(userId, ADS, snap);
        log.info("perf ads: salvo user={} advertiser={} ({} un., custo {})",
                userId, advertiserId, totals.path("units").asInt(), totals.path("cost").asDouble());
        return snap;
    }

    public JsonNode buildVisits(long userId) {
        JsonNode inv = loadInventory(userId);
        List<JsonNode> items = new ArrayList<>();
        if (inv != null) {
            for (JsonNode it : inv.path("items")) {
                if ("active".equals(txt(it, "status")) && txt(it, "id") != null && txt(it, "date_created") != null) {
                    items.add(it);
                }
            }
        }
        log.info("perf visitas: scan user={} ({} anúncios ativos)", userId, items.size());

        ObjectNode byItem = M.createObjectNode();
        final int CHUNK = 200;
        for (int start = 0; start < items.size(); start += CHUNK) {
            List<JsonNode> chunk = items.subList(start, Math.min(start + CHUNK, items.size()));
            List<Integer> totals = client.parallelMap(chunk,
                    it -> itemVisitsSince(txt(it, "id"), txt(it, "date_created")));
            for (int i = 0; i < chunk.size(); i++) byItem.put(txt(chunk.get(i), "id"), totals.get(i));
            log.info("perf visitas: user={} {}/{}", userId, Math.min(start + CHUNK, items.size()), items.size());
        }

        ObjectNode snap = M.createObjectNode();
        snap.put("user_id", userId);
        snap.put("scanned_at", Instant.now().toString());
        snap.set("by_item", byItem);
        save(userId, VISITS, snap);
        log.info("perf visitas: salvo user={} ({} itens)", userId, byItem.size());
        return snap;
    }

    private String resolveMode(long userId, String mode) {
        if ("full".equals(mode) || "light".equals(mode)) return mode;
        PerfSnapshot inv = repo.findByUserIdAndKind(userId, INVENTORY).orElse(null);
        if (inv == null) return "full";
        long ageHours = ChronoUnit.HOURS.between(inv.getScannedAt(), Instant.now());
        return ageHours <= INVENTORY_MAX_AGE_HOURS ? "light" : "full";
    }

    public Map<String, Object> refreshSnapshot(long userId, String nickname, int lookbackDays, String mode) {
        String eff = resolveMode(userId, mode);
        log.info("perf: refresh user={} mode={}→{}", userId, mode, eff);

        JsonNode inv, sales, ads, visits;
        if ("full".equals(eff)) {
            inv = buildInventory(userId, nickname);
            sales = buildSales(userId, lookbackDays, false);
            ads = buildAds(userId, lookbackDays);
            visits = buildVisits(userId); // depende do inventário recém-montado
        } else {
            sales = buildSales(userId, lookbackDays, true);
            ads = buildAds(userId, lookbackDays);
            inv = loadInventory(userId);
            visits = loadVisits(userId);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", eff);
        out.put("inventory_scanned_at", inv == null ? null : txt(inv, "scanned_at"));
        out.put("inventory_count", inv == null ? 0 : inv.path("items").size());
        out.put("sales_scanned_at", txt(sales, "scanned_at"));
        out.put("ads_scanned_at", txt(ads, "scanned_at"));
        out.put("visits_scanned_at", visits == null ? null : txt(visits, "scanned_at"));
        return out;
    }

    // ---------- ads sob demanda ----------

    private Long advertiserIdFor(long userId) {
        JsonNode cached = loadAds(userId);
        if (cached != null && cached.hasNonNull("advertiser_id")) return cached.get("advertiser_id").asLong();
        try {
            MeliResponse adv = client.get("/advertising/advertisers",
                    Map.of("product_id", "PADS"), Map.of("Api-Version", "1"), userId);
            JsonNode advs = (adv.data() == null ? M.createObjectNode() : adv.data()).path("advertisers");
            if (advs.isArray() && !advs.isEmpty()) {
                JsonNode a = advs.get(0);
                if (a.hasNonNull("advertiser_id")) return a.get("advertiser_id").asLong();
                if (a.hasNonNull("id")) return a.get("id").asLong();
            }
        } catch (Exception e) {
            log.warn("perf ads: advertiser lookup user={}: {}", userId, e.getMessage());
        }
        return null;
    }

    public Map<String, Object> getItemAds(String itemId, long userId, int days) {
        Long advertiserId = advertiserIdFor(userId);
        if (advertiserId == null) return null;
        days = Math.min(Math.max(1, days), MAX_ADS_WINDOW_DAYS);
        LocalDate end = LocalDate.now(ZoneOffset.UTC);
        String base = "/advertising/advertisers/" + advertiserId + "/product_ads/items";
        Map<String, String> params = new LinkedHashMap<>();
        params.put("filters[item_id]", itemId);
        params.put("date_from", end.minusDays(days).toString());
        params.put("date_to", end.toString());
        params.put("metrics", ADS_METRICS);
        MeliResponse r = client.get(base, params, Map.of("Api-Version", "2"), userId);
        if (r.status() != 200) {
            log.warn("perf ads item {}: status {}", itemId, r.status());
            return null;
        }
        JsonNode results = (r.data() == null ? M.createObjectNode() : r.data()).path("results");
        if (!results.isArray() || results.isEmpty()) {
            Map<String, Object> none = new LinkedHashMap<>();
            none.put("item_id", itemId); none.put("has_activity", false); none.put("days", days);
            return none;
        }
        JsonNode row = results.get(0);
        JsonNode m = row.path("metrics");
        int clicks = m.path("clicks").asInt(0), prints = m.path("prints").asInt(0);
        double cost = round2(m.path("cost").asDouble(0));
        int units = m.path("units_quantity").asInt(0);
        double amount = round2(m.path("total_amount").asDouble(0));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("item_id", itemId);
        out.put("days", days);
        out.put("has_activity", clicks != 0 || prints != 0 || cost != 0);
        out.put("campaign_id", row.get("campaign_id"));
        out.put("ad_status", txt(row, "status"));
        out.put("clicks", clicks);
        out.put("prints", prints);
        out.put("cost", cost);
        out.put("units", units);
        out.put("amount", amount);
        out.put("acos", amount > 0 ? round2(cost / amount * 100) : null);
        out.put("cpc", clicks > 0 ? round2(cost / clicks) : null);
        out.put("ctr", prints > 0 ? round2((double) clicks / prints * 100) : null);
        out.put("conversion", clicks > 0 ? round2((double) units / clicks * 100) : null);
        out.put("direct_units", m.path("direct_units_quantity").asInt(0));
        out.put("indirect_units", m.path("indirect_units_quantity").asInt(0));
        return out;
    }

    public List<Integer> getItemAdsSeries(String itemId, long userId, List<String> dates) {
        Long advertiserId = advertiserIdFor(userId);
        if (advertiserId == null) return dates.stream().map(d -> 0).toList();
        String base = "/advertising/advertisers/" + advertiserId + "/product_ads/items";
        return client.parallelMap(dates, day -> {
            try {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("filters[item_id]", itemId);
                params.put("date_from", day); params.put("date_to", day);
                params.put("metrics", ADS_METRICS);
                MeliResponse r = client.get(base, params, Map.of("Api-Version", "2"), userId);
                if (r.status() != 200) return 0;
                JsonNode results = (r.data() == null ? M.createObjectNode() : r.data()).path("results");
                if (!results.isArray() || results.isEmpty()) return 0;
                return results.get(0).path("metrics").path("units_quantity").asInt(0);
            } catch (Exception e) {
                return 0;
            }
        });
    }

    // ---------- visitas (sob demanda) ----------

    /** Série diária de visitas + total autoritativo do período (ver probe_visits.py). */
    public Map<String, Object> itemVisitsSeries(String itemId, int days, boolean withSeries) {
        LocalDate end = LocalDate.now(ZoneOffset.UTC);
        LocalDate start = end.minusDays(days);

        JsonNode seriesData = M.createObjectNode();
        if (withSeries) {
            try {
                MeliResponse sr = client.get("/items/" + itemId + "/visits/time_window",
                        Map.of("last", String.valueOf(days), "unit", "day"), null);
                if (sr.data() != null) seriesData = sr.data();
            } catch (Exception e) {
                log.warn("perf: visits time_window falhou item={}: {}", itemId, e.getMessage());
            }
        }
        JsonNode results = seriesData.path("results");
        List<String> dates = new ArrayList<>();
        List<Integer> series = new ArrayList<>();
        int sum = 0;
        for (JsonNode r : results) {
            String d = r.path("date").asText("");
            dates.add(d.length() >= 10 ? d.substring(0, 10) : d);
            int v = r.path("total").asInt(0);
            series.add(v);
            sum += v;
        }
        int total = seriesData.path("total_visits").asInt(sum);

        try {
            MeliResponse tr = client.get("/items/" + itemId + "/visits",
                    Map.of("date_from", start.toString(), "date_to", end.toString()), null);
            if (tr.status() == 200 && tr.data() != null && tr.data().hasNonNull("total_visits")) {
                total = tr.data().get("total_visits").asInt(0);
            }
        } catch (Exception e) {
            log.warn("perf: visits range falhou item={}: {}", itemId, e.getMessage());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("dates", dates);
        out.put("series", series);
        return out;
    }

    /** Visitas "vitalícias" (do date_from de criação até hoje), com teto de ~2 anos do ML. */
    public int itemVisitsSince(String itemId, String dateFromIso) {
        LocalDate end = LocalDate.now(ZoneOffset.UTC);
        LocalDate floor = end.minusDays(VISITS_MAX_LOOKBACK_DAYS);
        LocalDate start = floor;
        if (dateFromIso != null) {
            try {
                start = LocalDate.parse(dateFromIso.substring(0, 10));
            } catch (Exception e) {
                start = floor;
            }
        }
        if (start.isBefore(floor)) start = floor;
        try {
            MeliResponse r = client.get("/items/" + itemId + "/visits",
                    Map.of("date_from", start.toString(), "date_to", end.toString()), null);
            if (r.status() != 200 || r.data() == null) return 0;
            return r.data().path("total_visits").asInt(0);
        } catch (Exception e) {
            log.warn("perf: visits vitalício falhou item={}: {}", itemId, e.getMessage());
            return 0;
        }
    }

    public Map<String, Object> itemQuestions(String itemId) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("item", itemId); params.put("limit", "10");
            params.put("sort_fields", "date_created"); params.put("sort_types", "DESC");
            params.put("api_version", "4");
            MeliResponse resp = client.get("/questions/search", params, null);
            JsonNode data = resp.data() == null ? M.createObjectNode() : resp.data();
            int total = data.path("paging").path("total").asInt(0);
            JsonNode questions = data.has("questions") ? data.path("questions") : data.path("results");
            List<String> recent = new ArrayList<>();
            for (JsonNode q : questions) {
                if (recent.size() >= 10) break;
                String d = q.path("date_created").asText("");
                recent.add(d.length() >= 10 ? d.substring(0, 10) : d);
            }
            out.put("total", total);
            out.put("recent_dates", recent);
        } catch (Exception e) {
            log.warn("perf: questions falhou item={}: {}", itemId, e.getMessage());
            out.put("total", 0);
            out.put("recent_dates", List.of());
        }
        return out;
    }

    // ---------- helpers ----------

    // package-private: testável (janelas não podem se sobrepor, senão o custo de
    // ads é contado em dobro).
    static List<String[]> dateWindows(LocalDate endDate, int lookbackDays, int maxSpan) {
        List<String[]> windows = new ArrayList<>();
        LocalDate curEnd = endDate;
        int remaining = Math.max(1, lookbackDays);
        while (remaining > 0) {
            int span = Math.min(remaining, maxSpan);
            LocalDate curStart = curEnd.minusDays(span);
            windows.add(new String[]{curStart.toString(), curEnd.toString()});
            curEnd = curStart.minusDays(1);
            remaining -= span;
        }
        return windows;
    }

    private static String extractSku(JsonNode item) {
        for (JsonNode attr : item.path("attributes")) {
            if ("SELLER_SKU".equals(txt(attr, "id")) && attr.hasNonNull("value_name")) {
                return attr.get("value_name").asText().trim();
            }
        }
        String scf = txt(item, "seller_custom_field");
        return scf == null ? "" : scf.trim();
    }

    private static String txt(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static String firstNonEmpty(String... vals) {
        for (String v : vals) if (v != null && !v.isEmpty()) return v;
        return "";
    }

    private static int asInt(Object o) { return o instanceof Number n ? n.intValue() : 0; }
    private static double asDbl(Object o) { return o instanceof Number n ? n.doubleValue() : 0.0; }
    private static void addInt(ObjectNode o, String k, int v) { o.put(k, o.path(k).asInt(0) + v); }
    private static void addDbl(ObjectNode o, String k, double v) { o.put(k, o.path(k).asDouble(0) + v); }
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static String clip(String s, int n) { return s == null ? "" : (s.length() > n ? s.substring(0, n) : s); }
}
