package com.hrb.mlmanager.meli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hrb.mlmanager.meli.MeliClient.MeliResponse;
import com.hrb.mlmanager.ops.OperationLog;
import com.hrb.mlmanager.ops.OperationLogRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Gestao de promocoes via seller-promotions v2. Espelho de meli_promotions.py. */
@Service
public class MeliPromotionsService {

    private static final Logger log = LoggerFactory.getLogger(MeliPromotionsService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> PRICE_CHOICE_TYPES =
            Set.of("DEAL", "LIGHTNING", "DOD", "PRICE_DISCOUNT", "PRE_NEGOTIATED");
    private static final Map<String, String> V2 = Map.of("app_version", "v2");
    private static final Map<String, String> PARTICIPATING_STATUS = Map.of("LIGHTNING", "pending");
    private static final Pattern MLB_RE = Pattern.compile("^MLB\\d+$", Pattern.CASE_INSENSITIVE);
    private static final Map<String, String> CATEGORY_NAME_CACHE = new ConcurrentHashMap<>();
    private static final int PAGE_LIMIT = 50;

    private final MeliClient client;
    private final MeliAuthService auth;
    private final OperationLogRepository logs;

    public MeliPromotionsService(MeliClient client, MeliAuthService auth, OperationLogRepository logs) {
        this.client = client;
        this.auth = auth;
        this.logs = logs;
    }

    // ---- Leitura ------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listPromotions(long userId) {
        MeliResponse resp = client.get("/seller-promotions/users/" + userId, V2, userId);
        if (resp.status() != 200) {
            throw new IllegalStateException("ML retornou " + resp.status() + " ao listar promocoes: " + resp.data());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode p : results(resp.data())) {
            if ("SELLER_COUPON_CAMPAIGN".equals(p.path("type").asText())) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", text(p, "id"));
            row.put("type", text(p, "type"));
            row.put("name", text(p, "name"));
            row.put("status", text(p, "status"));
            row.put("start_date", text(p, "start_date"));
            row.put("finish_date", text(p, "finish_date"));
            row.put("deadline_date", text(p, "deadline_date"));
            row.put("fixed_percentage", num(p.get("fixed_percentage")));
            row.put("sub_type", text(p, "sub_type"));
            row.put("price_choice", PRICE_CHOICE_TYPES.contains(p.path("type").asText()));
            out.add(row);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listCoupons(long userId) {
        MeliResponse resp = client.get("/seller-promotions/users/" + userId, V2, userId);
        if (resp.status() != 200) {
            throw new IllegalStateException("ML retornou " + resp.status() + " ao listar cupons: " + resp.data());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode p : results(resp.data())) {
            if (!"SELLER_COUPON_CAMPAIGN".equals(p.path("type").asText())) continue;
            JsonNode detail = couponDetail(userId, p.path("id").asText());
            JsonNode m = merge(p, detail);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", text(m, "id"));
            row.put("name", text(m, "name"));
            row.put("status", text(m, "status"));
            row.put("sub_type", text(m, "sub_type"));
            row.put("fixed_percentage", num(m.get("fixed_percentage")));
            row.put("min_purchase_amount", num(m.get("min_purchase_amount")));
            row.put("max_purchase_amount", num(m.get("max_purchase_amount")));
            row.put("redeems_per_user", nullableInt(m.get("redeems_per_user")));
            row.put("budget", num(m.get("budget")));
            row.put("remaining_budget", num(m.get("remaining_budget")));
            row.put("used_coupons", nullableInt(m.get("used_coupons")));
            row.put("start_date", text(m, "start_date"));
            row.put("finish_date", text(m, "finish_date"));
            out.add(row);
        }
        return out;
    }

    private JsonNode couponDetail(long userId, String couponId) {
        try {
            Map<String, String> params = params("promotion_type", "SELLER_COUPON_CAMPAIGN");
            MeliResponse resp = client.get("/seller-promotions/promotions/" + couponId, params, userId);
            return resp.status() == 200 ? resp.data() : null;
        } catch (Exception e) {
            log.warn("Falha ao buscar detalhe do cupom {}: {}", couponId, e.getMessage());
            return null;
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> listPromotionItems(long userId, String promotionId, String promotionType,
                                                  String status, String searchAfter) {
        if ("started".equals(status)) status = PARTICIPATING_STATUS.getOrDefault(promotionType, "started");

        Map<String, String> params = params("promotion_type", promotionType, "limit", String.valueOf(PAGE_LIMIT));
        if (status != null && !status.isBlank()) params.put("status", status);
        if (searchAfter != null && !searchAfter.isBlank()) params.put("search_after", searchAfter);

        MeliResponse resp = client.get("/seller-promotions/promotions/" + promotionId + "/items", params, userId);
        if (resp.status() != 200) {
            throw new IllegalStateException("ML retornou " + resp.status() + " ao listar itens: " + resp.data());
        }

        JsonNode data = resp.data();
        List<JsonNode> raw = array(data == null ? null : data.path("results"));
        raw = preferRequestedStatus(raw, status);
        DedupResult dedup = dedup(raw);

        List<String> itemIds = dedup.rows().stream().map(n -> n.path("id").asText()).filter(s -> !s.isBlank()).toList();
        Map<String, JsonNode> meta = itemMeta(itemIds, userId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (JsonNode r : dedup.rows()) {
            String id = r.path("id").asText("");
            Map<String, Object> item = normalizeItem(promotionType, r, meta.get(id));
            double[] range = dedup.priceRange().get(id);
            if (range != null) {
                item.put("promo_price_min", range[0]);
                item.put("promo_price_max", range[1]);
            }
            items.add(item);
        }
        resolveCategoryNames(items);

        JsonNode paging = data == null ? null : data.path("paging");
        Map<String, Object> pagingOut = new LinkedHashMap<>();
        pagingOut.put("total", paging == null ? null : nullIfMissing(paging.path("total")));
        pagingOut.put("search_after", paging == null ? null : nullIfMissing(paging.path("searchAfter")));
        return Map.of("items", items, "paging", pagingOut);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> searchInPromotion(long userId, String promotionId, String promotionType, String q) {
        List<String> ids = resolveToItemIds(userId, q);
        if (ids.isEmpty()) return Map.of("items", List.of(), "messages", List.of("Nenhum anuncio encontrado para \"" + q + "\"."));

        List<Map.Entry<String, JsonNode>> matched = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        for (String itemId : ids) {
            try {
                MeliResponse resp = client.get("/seller-promotions/items/" + itemId, V2, userId);
                if (resp.status() != 200 || resp.data() == null || !resp.data().isArray()) {
                    messages.add(itemId + ": nao foi possivel consultar as promocoes.");
                    continue;
                }
                JsonNode entry = null;
                for (JsonNode p : resp.data()) {
                    if (promotionId.equals(p.path("id").asText())) {
                        entry = p;
                        break;
                    }
                }
                if (entry == null) {
                    messages.add(itemId + " nao esta nesta promocao (nem elegivel, nem participando).");
                } else {
                    matched.add(Map.entry(itemId, entry));
                }
            } catch (Exception e) {
                log.warn("Erro ao consultar promocoes do item {}: {}", itemId, e.getMessage());
                messages.add(itemId + ": nao foi possivel consultar as promocoes.");
            }
        }

        Map<String, JsonNode> meta = itemMeta(matched.stream().map(Map.Entry::getKey).toList(), userId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : matched) {
            ObjectNode row = e.getValue().deepCopy();
            row.put("id", e.getKey());
            items.add(normalizeItem(promotionType, row, meta.get(e.getKey())));
        }
        resolveCategoryNames(items);
        return Map.of("items", items, "messages", messages);
    }

    // ---- Escrita ------------------------------------------------------------

    @Transactional
    public Map<String, Object> createCoupon(long userId, JsonNode payload) {
        ObjectNode body = cleanObject(payload);
        body.put("promotion_type", "SELLER_COUPON_CAMPAIGN");
        try {
            MeliResponse resp = client.post("/seller-promotions/promotions", V2, userId, body);
            String couponId = resp.data() == null ? null : resp.data().path("id").asText(null);
            boolean ok = logCouponOp("coupon_create", userId, couponId, resp, body);
            return writeResult(ok, resp);
        } catch (Exception e) {
            log.warn("Erro ao criar cupom: {}", e.getMessage());
            return Map.of("ok", false, "status", 0, "error", e.getMessage());
        }
    }

    @Transactional
    public Map<String, Object> updateCoupon(long userId, String couponId, JsonNode payload) {
        ObjectNode body = cleanObject(payload);
        body.put("promotion_type", "SELLER_COUPON_CAMPAIGN");
        try {
            MeliResponse resp = client.put("/seller-promotions/promotions/" + couponId, V2, userId, body);
            boolean ok = logCouponOp("coupon_update", userId, couponId, resp, body);
            return writeResult(ok, resp);
        } catch (Exception e) {
            log.warn("Erro ao editar cupom {}: {}", couponId, e.getMessage());
            return Map.of("ok", false, "status", 0, "error", e.getMessage());
        }
    }

    @Transactional
    public Map<String, Object> endCoupon(long userId, String couponId) {
        try {
            Map<String, String> params = params("promotion_type", "SELLER_COUPON_CAMPAIGN", "user_id", String.valueOf(userId));
            MeliResponse resp = client.delete("/seller-promotions/promotions/" + couponId, params, userId);
            boolean ok = logCouponOp("coupon_delete", userId, couponId, resp, MAPPER.createObjectNode().put("id", couponId));
            return writeResult(ok, resp);
        } catch (Exception e) {
            log.warn("Erro ao encerrar cupom {}: {}", couponId, e.getMessage());
            return Map.of("ok", false, "status", 0, "error", e.getMessage());
        }
    }

    @Transactional
    public Map<String, Object> addItems(long userId, String promotionId, String promotionType, List<JsonNode> items) {
        String batchId = UUID.randomUUID().toString().replace("-", "");
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, JsonNode> inputById = new LinkedHashMap<>();
        for (JsonNode item : items) {
            String itemId = item.path("item_id").asText();
            inputById.put(itemId, item);
            ObjectNode body = buildBody(promotionId, promotionType, item);
            try {
                MeliResponse resp = client.post("/seller-promotions/items/" + itemId, V2, userId, body);
                results.add(actionResult(itemId, resp.status() == 200 || resp.status() == 201, resp.status(), resp.data(), null));
            } catch (Exception e) {
                results.add(actionResult(itemId, false, 0, null, e.getMessage()));
            }
        }
        JsonNode payload = MAPPER.valueToTree(buildLogPayload(userId, promotionId, promotionType,
                new ArrayList<>(inputById.keySet()), inputById));
        return finish("promotion_add", userId, batchId, results, payload);
    }

    @Transactional
    public Map<String, Object> removeItems(long userId, String promotionId, String promotionType, List<String> itemIds) {
        String batchId = UUID.randomUUID().toString().replace("-", "");
        List<Map<String, Object>> results = new ArrayList<>();
        for (String itemId : itemIds) {
            try {
                Map<String, String> params = params("promotion_id", promotionId, "promotion_type", promotionType);
                MeliResponse resp = client.delete("/seller-promotions/items/" + itemId, params, userId);
                results.add(actionResult(itemId,
                        resp.status() == 200 || resp.status() == 201 || resp.status() == 204,
                        resp.status(), resp.data(), null));
            } catch (Exception e) {
                results.add(actionResult(itemId, false, 0, null, e.getMessage()));
            }
        }
        JsonNode payload = MAPPER.valueToTree(buildLogPayload(userId, promotionId, promotionType, itemIds, Map.of()));
        return finish("promotion_remove", userId, batchId, results, payload);
    }

    // ---- Normalizacao -------------------------------------------------------

    private Map<String, Object> normalizeItem(String promotionType, JsonNode r, JsonNode meta) {
        if (meta == null) meta = MAPPER.createObjectNode();
        String id = r.path("id").asText(null);
        Double original = num(r.get("original_price"));
        Double price = num(r.get("price"));
        Double fixedPct = num(r.get("fixed_percentage"));

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("title", nullIfMissing(meta.path("title")));
        item.put("sku", extractSku(meta));
        item.put("thumbnail", nullIfMissing(meta.path("thumbnail")));
        item.put("category_id", nullIfMissing(meta.path("category_id")));
        item.put("category_name", null);
        item.put("status", text(r, "status"));
        item.put("current_price", original);
        String refId = r.path("ref_id").asText("");
        item.put("offer_id", r.hasNonNull("offer_id") ? r.path("offer_id").asText()
                : refId.startsWith("OFFER-") ? refId : null);
        item.put("price_editable", PRICE_CHOICE_TYPES.contains(promotionType));
        item.put("min_price", null);
        item.put("max_price", null);
        item.put("suggested_price", null);
        item.put("stock_min", null);
        item.put("stock_max", null);
        item.put("meli_percentage", num(r.get("meli_percentage")));
        item.put("seller_percentage", num(r.get("seller_percentage")));
        item.put("fixed_percentage", fixedPct);

        Double promoPrice;
        if (PRICE_CHOICE_TYPES.contains(promotionType)) {
            Double min = num(r.get("min_discounted_price"));
            Double suggested = num(r.get("suggested_discounted_price"));
            Double explicitMax = num(r.get("max_discounted_price"));
            Double discountedPrice = price != null && original != null && price > 0 && price < original ? price : null;
            Double safeSuggestion = suggested != null ? suggested : discountedPrice;
            Double max = explicitMax != null ? explicitMax : safeSuggestion;
            String st = r.path("status").asText("");
            Double chosen = (st.equals("started") || st.equals("pending")) ? price : safeSuggestion;
            item.put("min_price", min);
            item.put("max_price", max);
            item.put("suggested_price", suggested != null ? suggested : chosen);
            promoPrice = chosen;
            JsonNode stock = r.path("stock");
            if (stock.isObject()) {
                item.put("stock_min", nullIfMissing(stock.path("min")));
                item.put("stock_max", nullIfMissing(stock.path("max")));
            }
        } else if (fixedPct != null) {
            promoPrice = original == null ? null : Math.round(original * (1 - fixedPct / 100.0) * 100.0) / 100.0;
        } else {
            promoPrice = price;
        }
        item.put("promo_price", promoPrice);
        item.put("promo_price_min", promoPrice);
        item.put("promo_price_max", promoPrice);
        item.put("discount_pct", discountPct(original, promoPrice));
        return item;
    }

    private ObjectNode buildBody(String promotionId, String promotionType, JsonNode item) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("promotion_id", promotionId);
        body.put("promotion_type", promotionType);
        if (PRICE_CHOICE_TYPES.contains(promotionType) && item.hasNonNull("deal_price")) {
            body.set("deal_price", item.get("deal_price"));
            if (item.hasNonNull("top_deal_price")) body.set("top_deal_price", item.get("top_deal_price"));
        }
        if ("LIGHTNING".equals(promotionType) && item.hasNonNull("stock")) body.set("stock", item.get("stock"));
        if (item.hasNonNull("offer_id")) body.set("offer_id", item.get("offer_id"));
        return body;
    }

    private List<String> resolveToItemIds(long userId, String q) {
        String clean = q == null ? "" : q.trim();
        if (MLB_RE.matcher(clean).matches()) return List.of(clean.toUpperCase());
        for (String key : List.of("seller_sku", "sku")) {
            MeliResponse resp = client.get("/users/" + userId + "/items/search",
                    Map.of(key, clean, "limit", "50"), userId);
            JsonNode results = resp.data() == null ? null : resp.data().path("results");
            if (results != null && results.isArray() && !results.isEmpty()) {
                List<String> out = new ArrayList<>();
                results.forEach(n -> out.add(n.asText()));
                return out;
            }
        }
        return List.of();
    }

    private Map<String, JsonNode> itemMeta(List<String> itemIds, long userId) {
        if (itemIds.isEmpty()) return Map.of();
        Map<String, JsonNode> out = new LinkedHashMap<>();
        try {
            for (JsonNode m : client.multiGetItems(itemIds,
                    List.of("id", "title", "thumbnail", "seller_custom_field", "attributes", "category_id"), userId)) {
                out.put(m.path("id").asText(), m);
            }
        } catch (Exception e) {
            log.warn("Falha ao enriquecer itens de promocao: {}", e.getMessage());
        }
        return out;
    }

    private void resolveCategoryNames(List<Map<String, Object>> items) {
        Set<String> missing = new LinkedHashSet<>();
        for (Map<String, Object> item : items) {
            Object id = item.get("category_id");
            if (id != null && !CATEGORY_NAME_CACHE.containsKey(String.valueOf(id))) missing.add(String.valueOf(id));
        }
        for (String cid : missing) {
            try {
                MeliResponse resp = client.getPublic("/categories/" + cid);
                if (resp.status() == 200 && resp.data() != null && resp.data().hasNonNull("name")) {
                    CATEGORY_NAME_CACHE.put(cid, resp.data().path("name").asText());
                }
            } catch (Exception e) {
                log.warn("Falha ao resolver categoria {}: {}", cid, e.getMessage());
            }
        }
        for (Map<String, Object> item : items) {
            Object id = item.get("category_id");
            if (id != null) item.put("category_name", CATEGORY_NAME_CACHE.get(String.valueOf(id)));
        }
    }

    // ---- Logs ---------------------------------------------------------------

    private boolean logCouponOp(String type, long userId, String couponId, MeliResponse resp, JsonNode payload) {
        boolean ok = resp.status() == 200 || resp.status() == 201 || resp.status() == 204;
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", resp.status());
        response.put("data", resp.data());
        OperationLog op = new OperationLog(type,
                couponId == null || couponId.isBlank() ? List.of() : List.of(couponId),
                payload,
                MAPPER.valueToTree(response),
                ok ? "success" : "error",
                ok ? null : "ML " + resp.status());
        op.setUserId(userId);
        op.setBatchId(UUID.randomUUID().toString().replace("-", ""));
        logs.save(op);
        return ok;
    }

    private Map<String, Object> buildLogPayload(long userId, String promotionId, String promotionType,
                                                List<String> itemIds, Map<String, JsonNode> inputById) {
        String promoName = null;
        try {
            promoName = listPromotions(userId).stream()
                    .filter(p -> promotionId.equals(p.get("id")))
                    .map(p -> p.get("name"))
                    .filter(v -> v != null)
                    .map(String::valueOf)
                    .findFirst().orElse(null);
        } catch (Exception ignored) {}

        Map<String, JsonNode> metas = itemMeta(itemIds, userId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (String id : itemIds) {
            JsonNode m = metas.get(id);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("title", m == null ? null : nullIfMissing(m.path("title")));
            row.put("sku", m == null ? null : extractSku(m));
            JsonNode input = inputById.get(id);
            if (input != null && input.hasNonNull("deal_price")) row.put("deal_price", input.get("deal_price"));
            items.add(row);
        }

        Map<String, Object> group = new LinkedHashMap<>();
        group.put("user_id", userId);
        group.put("nickname", nicknameFor(userId));
        group.put("item_ids", itemIds);
        group.put("items", items);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("promotion_id", promotionId);
        payload.put("promotion_type", promotionType);
        payload.put("promotion_name", promoName);
        payload.put("groups", List.of(group));
        return payload;
    }

    private Map<String, Object> finish(String type, long userId, String batchId,
                                       List<Map<String, Object>> results, JsonNode payload) {
        long succeeded = results.stream().filter(r -> Boolean.TRUE.equals(r.get("ok"))).count();
        long failed = results.size() - succeeded;
        String status = failed == 0 ? "success" : succeeded > 0 ? "partial" : "error";

        OperationLog op = new OperationLog(type,
                results.stream().map(r -> String.valueOf(r.get("item_id"))).toList(),
                payload, MAPPER.valueToTree(Map.of("results", results)), status,
                failed == 0 ? null : failed + " item(ns) falharam");
        op.setUserId(userId);
        op.setBatchId(batchId);
        logs.save(op);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", failed == 0);
        out.put("batch_id", batchId);
        out.put("succeeded", succeeded);
        out.put("failed", failed);
        out.put("results", results);
        return out;
    }

    // ---- Helpers ------------------------------------------------------------

    private static Map<String, Object> writeResult(boolean ok, MeliResponse resp) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok);
        out.put("status", resp.status());
        out.put("data", resp.data());
        return out;
    }

    private static Map<String, Object> actionResult(String itemId, boolean ok, int status, JsonNode data, String error) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("item_id", itemId);
        out.put("ok", ok);
        out.put("status", status);
        if (data != null) out.put("data", data);
        if (error != null) out.put("error", error);
        return out;
    }

    private static ObjectNode cleanObject(JsonNode node) {
        ObjectNode out = MAPPER.createObjectNode();
        if (node == null || !node.isObject()) return out;
        node.fields().forEachRemaining(e -> {
            if (!e.getValue().isNull()) out.set(e.getKey(), e.getValue());
        });
        return out;
    }

    private static JsonNode merge(JsonNode base, JsonNode extra) {
        ObjectNode out = MAPPER.createObjectNode();
        if (base != null && base.isObject()) base.fields().forEachRemaining(e -> out.set(e.getKey(), e.getValue()));
        if (extra != null && extra.isObject()) extra.fields().forEachRemaining(e -> out.set(e.getKey(), e.getValue()));
        return out;
    }

    private static List<JsonNode> results(JsonNode raw) {
        if (raw == null) return List.of();
        JsonNode arr = raw.has("results") ? raw.path("results") : raw;
        return array(arr);
    }

    private static List<JsonNode> array(JsonNode arr) {
        if (arr == null || !arr.isArray()) return List.of();
        List<JsonNode> out = new ArrayList<>();
        arr.forEach(out::add);
        return out;
    }

    private static List<JsonNode> preferRequestedStatus(List<JsonNode> results, String requestedStatus) {
        if (requestedStatus == null || requestedStatus.isBlank()) return results;
        List<JsonNode> exact = results.stream()
                .filter(row -> requestedStatus.equals(row.path("status").asText(null)))
                .toList();
        return exact.isEmpty() ? results : exact;
    }

    private static DedupResult dedup(List<JsonNode> results) {
        Set<String> seen = new LinkedHashSet<>();
        List<JsonNode> rows = new ArrayList<>();
        Map<String, double[]> range = new LinkedHashMap<>();
        for (JsonNode r : results) {
            String id = r.path("id").asText(null);
            if (id == null) continue;
            Double p = num(r.get("price"));
            if (p != null) {
                double[] rng = range.computeIfAbsent(id, k -> new double[] {p, p});
                rng[0] = Math.min(rng[0], p);
                rng[1] = Math.max(rng[1], p);
            }
            if (seen.add(id)) rows.add(r);
        }
        return new DedupResult(rows, range);
    }

    private static Map<String, String> params(String... kv) {
        Map<String, String> out = new LinkedHashMap<>(V2);
        for (int i = 0; i + 1 < kv.length; i += 2) out.put(kv[i], kv[i + 1]);
        return out;
    }

    private String nicknameFor(long userId) {
        for (Map<String, Object> account : auth.listAccounts()) {
            if (number(account.get("user_id")).longValue() == userId) return String.valueOf(account.get("nickname"));
        }
        return "Conta " + userId;
    }

    private static Double num(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.isNumber()) return node.asDouble();
        try {
            String s = node.asText();
            return s == null || s.isBlank() ? null : Double.parseDouble(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer nullableInt(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        return node.canConvertToInt() ? node.asInt() : null;
    }

    private static Double discountPct(Double ref, Double promo) {
        if (ref == null || promo == null || ref <= 0) return null;
        return Math.round((ref - promo) / ref * 1000.0) / 10.0;
    }

    private static String extractSku(JsonNode item) {
        if (item == null) return "";
        for (JsonNode attr : item.path("attributes")) {
            if ("SELLER_SKU".equals(attr.path("id").asText()) && attr.hasNonNull("value_name")) {
                return attr.path("value_name").asText("").trim();
            }
        }
        return item.path("seller_custom_field").asText("").trim();
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() || v.isMissingNode() ? null : v.asText();
    }

    private static Object nullIfMissing(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node;
    }

    private static Number number(Object value) {
        if (value instanceof Number n) return n;
        return Long.parseLong(String.valueOf(value));
    }

    private record DedupResult(List<JsonNode> rows, Map<String, double[]> priceRange) {}
}
