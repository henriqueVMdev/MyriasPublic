package com.hrb.mlmanager.meli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hrb.mlmanager.meli.MeliClient.MeliResponse;
import com.hrb.mlmanager.ops.OperationLog;
import com.hrb.mlmanager.ops.OperationLogRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Edicao em massa de anuncios. Porta a base de backend/app/services/meli_bulk.py:
 * agrupamento por SKU, updates multi-conta, descricao e diagnostico de pacote.
 */
@Service
public class MeliBulkService {

    private static final Logger log = LoggerFactory.getLogger(MeliBulkService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> SKU_FIELDS = List.of(
            "id", "title", "price", "available_quantity", "seller_custom_field",
            "attributes", "thumbnail", "status", "listing_type_id");

    private static final List<String> ITEM_FIELDS = List.of(
            "id", "title", "price", "available_quantity", "sold_quantity",
            "status", "pictures", "thumbnail", "permalink", "seller_custom_field",
            "attributes", "variations", "listing_type_id", "category_id", "shipping");

    private static final List<String> PACKAGE_ATTR_IDS = List.of(
            "PACKAGE_HEIGHT", "PACKAGE_WIDTH", "PACKAGE_LENGTH", "PACKAGE_WEIGHT");

    private final MeliClient client;
    private final MeliAuthService auth;
    private final OperationLogRepository logs;

    public MeliBulkService(MeliClient client, MeliAuthService auth, OperationLogRepository logs) {
        this.client = client;
        this.auth = auth;
        this.logs = logs;
    }

    private record PutOutcome(boolean ok, JsonNode error, List<String> skippedFields) {}

    // ---- SKUs ---------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getSkus(long userId) {
        List<String> allIds = client.scanAllItems(userId, null, null);
        log.info("Bulk SKUs user={}: {} itens encontrados", userId, allIds.size());
        if (allIds.isEmpty()) return List.of();

        List<JsonNode> items = client.multiGetItems(allIds, SKU_FIELDS, userId);
        Map<String, Map<String, Object>> skuMap = new LinkedHashMap<>();
        for (JsonNode item : items) {
            String sku = extractSku(item);
            if (sku == null || sku.isBlank()) continue;
            Map<String, Object> entry = skuMap.computeIfAbsent(sku, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("sku", k);
                m.put("count", 0);
                m.put("items_preview", new ArrayList<Map<String, Object>>());
                return m;
            });
            entry.put("count", ((Number) entry.get("count")).intValue() + 1);
            addPreview(entry, item, null);
        }
        return sortedByCount(skuMap);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getSkusAllAccounts() {
        Map<String, Map<String, Object>> skuMap = new LinkedHashMap<>();
        for (Map<String, Object> account : auth.listAccounts()) {
            long userId = number(account.get("user_id")).longValue();
            String nickname = String.valueOf(account.get("nickname"));
            try {
                List<String> allIds = client.scanAllItems(userId, null, null);
                if (allIds.isEmpty()) continue;
                for (JsonNode item : client.multiGetItems(allIds, SKU_FIELDS, userId)) {
                    String sku = extractSku(item);
                    if (sku == null || sku.isBlank()) continue;
                    Map<String, Object> entry = skuMap.computeIfAbsent(sku, k -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("sku", k);
                        m.put("count", 0);
                        m.put("accounts", new LinkedHashMap<Long, Map<String, Object>>());
                        m.put("items_preview", new ArrayList<Map<String, Object>>());
                        return m;
                    });
                    entry.put("count", ((Number) entry.get("count")).intValue() + 1);
                    @SuppressWarnings("unchecked")
                    Map<Long, Map<String, Object>> accounts =
                            (Map<Long, Map<String, Object>>) entry.get("accounts");
                    Map<String, Object> acc = accounts.computeIfAbsent(userId, k -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("user_id", userId);
                        m.put("nickname", nickname);
                        m.put("count", 0);
                        return m;
                    });
                    acc.put("count", ((Number) acc.get("count")).intValue() + 1);
                    addPreview(entry, item, account);
                }
            } catch (Exception e) {
                log.warn("Falha ao escanear conta {} para SKUs: {}", userId, e.getMessage());
            }
        }

        List<Map<String, Object>> out = sortedByCount(skuMap);
        for (Map<String, Object> entry : out) {
            @SuppressWarnings("unchecked")
            Map<Long, Map<String, Object>> accounts =
                    (Map<Long, Map<String, Object>>) entry.get("accounts");
            entry.put("accounts", new ArrayList<>(accounts.values()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<JsonNode> getItemsBySku(long userId, String sku) {
        List<String> itemIds = searchItemIdsBySku(userId, "seller_sku", sku);
        if (itemIds.isEmpty()) itemIds = searchItemIdsBySku(userId, "sku", sku);
        if (itemIds.isEmpty()) return List.of();
        return client.multiGetItems(itemIds, ITEM_FIELDS, userId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getItemsBySkuAllAccounts(String sku) {
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map<String, Object> account : auth.listAccounts()) {
            long userId = number(account.get("user_id")).longValue();
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("user_id", userId);
            group.put("nickname", String.valueOf(account.get("nickname")));
            try {
                List<JsonNode> items = getItemsBySku(userId, sku);
                group.put("items", items);
            } catch (Exception e) {
                group.put("items", List.of());
                group.put("error", e.getMessage());
                log.warn("Falha ao buscar SKU '{}' na conta {}: {}", sku, userId, e.getMessage());
            }
            @SuppressWarnings("unchecked")
            List<JsonNode> items = (List<JsonNode>) group.get("items");
            if (!items.isEmpty() || group.containsKey("error")) groups.add(group);
        }
        return groups;
    }

    // ---- Updates ------------------------------------------------------------

    @Transactional
    public Map<String, Object> bulkUpdate(List<String> itemIds, ObjectNode updates, Long userId) {
        boolean keepCover = updates.path("keep_cover_photo").asBoolean(false);
        ObjectNode baseUpdates = withoutKey(updates, "keep_cover_photo");

        Map<String, Object> results = newBulkResult(itemIds.size());
        for (String itemId : itemIds) {
            try {
                ObjectNode itemUpdates = withCoverIfRequested(itemId, userId, baseUpdates, keepCover);
                PutOutcome outcome = putItem(itemId, itemUpdates, userId);
                recordPutOutcome(results, itemId, outcome);
            } catch (Exception e) {
                errors(results).add(errorMap(itemId, e.getMessage()));
            }
        }

        saveLog("bulk_update", itemIds, updates, results, errors(results), userId, null);
        log.info("Bulk update: {}/{} sucesso, {} erros",
                results.get("success"), results.get("total"), errors(results).size());
        return results;
    }

    @Transactional
    public Map<String, Object> bulkUpdateBySku(long userId, String sku, ObjectNode updates) {
        List<JsonNode> items = getItemsBySku(userId, sku);
        if (items.isEmpty()) {
            Map<String, Object> out = newBulkResult(0);
            out.put("message", "Nenhum item encontrado com esse SKU");
            return out;
        }
        List<String> ids = items.stream().map(i -> i.path("id").asText()).toList();
        return bulkUpdate(ids, updates, userId);
    }

    @Transactional
    public Map<String, Object> bulkUpdateMultiAccount(List<Map<String, Object>> groups, ObjectNode updates,
                                                      String sku, Map<String, String> titles,
                                                      JsonNode before, String batchId) {
        if (groups.isEmpty()) {
            Map<String, Object> out = newBulkResult(0);
            out.put("accounts", List.of());
            return out;
        }

        List<Map<String, Object>> perAccount = new ArrayList<>();
        for (Map<String, Object> group : groups) {
            long userId = number(group.get("user_id")).longValue();
            List<String> itemIds = strings(group.get("item_ids"));
            perAccount.add(runGroupUpdate(userId, itemIds, updates));
        }

        int total = perAccount.stream().mapToInt(a -> ((Number) a.get("total")).intValue()).sum();
        int success = perAccount.stream().mapToInt(a -> ((Number) a.get("success")).intValue()).sum();
        List<Map<String, Object>> allErrors = new ArrayList<>();
        for (Map<String, Object> a : perAccount) allErrors.addAll(accountErrors(a));

        List<String> allIds = new ArrayList<>();
        for (Map<String, Object> g : groups) allIds.addAll(strings(g.get("item_ids")));
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("sku", sku);
        payload.set("updates", updates);
        payload.set("groups", MAPPER.valueToTree(enrichGroups(groups, titles, before)));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("per_account", perAccount);
        response.put("total", total);
        response.put("success", success);
        saveLog("bulk_update_multi_account", allIds, payload, response, allErrors, null, batchId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("success", success);
        out.put("errors", allErrors);
        out.put("accounts", perAccount);
        return out;
    }

    // ---- Descricao ----------------------------------------------------------

    @Transactional(readOnly = true)
    public String getItemDescription(String itemId, long userId) {
        MeliResponse resp = client.get("/items/" + itemId + "/description", userId);
        JsonNode data = resp.data();
        if (data == null) return "";
        String plain = data.path("plain_text").asText(null);
        if (plain != null) return plain;
        return data.path("text").asText("");
    }

    @Transactional
    public Map<String, Object> bulkUpdateDescriptionMulti(List<Map<String, Object>> groups,
                                                          String description, String sku, String batchId) {
        List<Map<String, Object>> perAccount = new ArrayList<>();
        for (Map<String, Object> group : groups) {
            long userId = number(group.get("user_id")).longValue();
            List<String> itemIds = strings(group.get("item_ids"));
            Map<String, Object> per = newAccountResult(userId, itemIds.size());
            for (String itemId : itemIds) {
                try {
                    ObjectNode body = MAPPER.createObjectNode().put("plain_text", description);
                    MeliResponse resp = client.put("/items/" + itemId + "/description", userId, body);
                    if (resp.status() == 200 || resp.status() == 201) {
                        incrementSuccess(per);
                    } else {
                        accountErrors(per).add(errorMap(itemId, resp.data()));
                    }
                } catch (Exception e) {
                    accountErrors(per).add(errorMap(itemId, e.getMessage()));
                }
            }
            perAccount.add(per);
        }

        int total = perAccount.stream().mapToInt(a -> ((Number) a.get("total")).intValue()).sum();
        int success = perAccount.stream().mapToInt(a -> ((Number) a.get("success")).intValue()).sum();
        List<Map<String, Object>> errors = new ArrayList<>();
        for (Map<String, Object> a : perAccount) errors.addAll(accountErrors(a));
        List<String> allIds = new ArrayList<>();
        for (Map<String, Object> g : groups) allIds.addAll(strings(g.get("item_ids")));

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("sku", sku);
        payload.put("description_length", description.length());
        Map<String, Object> response = Map.of("per_account", perAccount, "total", total, "success", success);
        saveLog("bulk_update_description", allIds, payload, response, errors, null, batchId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("success", success);
        out.put("errors", errors);
        out.put("accounts", perAccount);
        return out;
    }

    // ---- Diagnosticos / atributos ------------------------------------------

    @Transactional(readOnly = true)
    public Map<String, Object> debugItemPackage(String itemId, long userId) {
        MeliResponse resp = client.get("/items/" + itemId, userId);
        JsonNode body = resp.data();
        ArrayNode packageAttrs = MAPPER.createArrayNode();
        if (body != null && body.path("attributes").isArray()) {
            for (JsonNode attr : body.path("attributes")) {
                if (PACKAGE_ATTR_IDS.contains(attr.path("id").asText())) {
                    packageAttrs.add(attr);
                }
            }
        }
        JsonNode shipping = body == null ? null : body.path("shipping");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("item_id", itemId);
        out.put("status", resp.status());
        out.put("catalog_product_id", body == null ? null : nullIfMissing(body.path("catalog_product_id")));
        out.put("catalog_listing", body != null && body.path("catalog_listing").asBoolean(false));
        out.put("shipping_dimensions", shipping == null ? null : nullIfMissing(shipping.path("dimensions")));
        out.put("package_attributes", packageAttrs);
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getPositionAttributes(String categoryId, long userId) {
        List<String> hints = List.of("POSIT", "SIDE", "AXIS", "INSTALLATION", "LOCATION");
        List<String> exclude = List.of("IMPACT", "POSITIVE_IMPACT");
        MeliResponse resp = client.get("/categories/" + categoryId + "/attributes", userId);
        List<Map<String, Object>> out = new ArrayList<>();
        JsonNode attrs = resp.data();
        if (attrs == null || !attrs.isArray()) return out;
        for (JsonNode attr : attrs) {
            String id = attr.path("id").asText("").toUpperCase();
            if (exclude.stream().anyMatch(id::contains) || hints.stream().noneMatch(id::contains)) continue;
            String valueType = attr.path("value_type").asText("");
            if (!valueType.equals("list") && !valueType.equals("string")) continue;
            List<Map<String, Object>> values = new ArrayList<>();
            for (JsonNode v : attr.path("values")) {
                if (v.hasNonNull("id")) {
                    values.add(Map.of("id", v.path("id").asText(), "name", v.path("name").asText("")));
                }
            }
            if (!values.isEmpty()) {
                out.add(Map.of("id", attr.path("id").asText(), "name", attr.path("name").asText(""), "values", values));
            }
        }
        return out;
    }

    // ---- Internos -----------------------------------------------------------

    private Map<String, Object> runGroupUpdate(long userId, List<String> itemIds, ObjectNode updates) {
        Map<String, Object> per = newAccountResult(userId, itemIds.size());
        boolean keepCover = updates.path("keep_cover_photo").asBoolean(false);
        ObjectNode baseUpdates = withoutKey(updates, "keep_cover_photo");
        for (String itemId : itemIds) {
            try {
                ObjectNode itemUpdates = withCoverIfRequested(itemId, userId, baseUpdates, keepCover);
                recordPutOutcome(per, itemId, putItem(itemId, itemUpdates, userId));
            } catch (Exception e) {
                accountErrors(per).add(errorMap(itemId, e.getMessage()));
            }
        }
        return per;
    }

    private PutOutcome putItem(String itemId, ObjectNode updates, Long userId) {
        MeliResponse resp = client.put("/items/" + itemId, userId, updates);
        if (resp.status() == 200) return new PutOutcome(true, null, List.of());

        log.warn("_put_item {} status={} payload_keys={} resp={}",
                itemId, resp.status(), iterableKeys(updates), String.valueOf(resp.data()));
        List<String> blocked = nonUpdatableFields(resp.data());
        if (blocked.isEmpty()) return new PutOutcome(false, resp.data(), List.of());

        ObjectNode retry = updates.deepCopy();
        List<String> skipped = new ArrayList<>();
        for (String fieldPath : blocked) {
            String top = fieldPath.split("\\.")[0];
            if (retry.has(top)) {
                retry.remove(top);
                skipped.add(fieldPath);
            }
        }
        if (retry.isEmpty()) return new PutOutcome(false, resp.data(), List.of());

        MeliResponse retryResp = client.put("/items/" + itemId, userId, retry);
        if (retryResp.status() == 200) return new PutOutcome(true, null, skipped);
        return new PutOutcome(false, retryResp.data(), List.of());
    }

    private ObjectNode withCoverIfRequested(String itemId, Long userId, ObjectNode baseUpdates, boolean keepCover) {
        if (!keepCover || !baseUpdates.path("pictures").isArray()) return baseUpdates.deepCopy();
        MeliResponse resp = client.get("/items/" + itemId, Map.of("attributes", "pictures"), userId);
        JsonNode currentPics = resp.data() == null ? null : resp.data().path("pictures");
        if (currentPics == null || !currentPics.isArray() || currentPics.isEmpty()) return baseUpdates.deepCopy();

        JsonNode cover = currentPics.get(0);
        ObjectNode coverEntry = MAPPER.createObjectNode();
        String coverId = cover.path("id").asText("");
        if (!coverId.isBlank()) {
            coverEntry.put("id", coverId);
        } else {
            coverEntry.put("source", cover.path("secure_url").asText(""));
        }
        ArrayNode pictures = MAPPER.createArrayNode();
        pictures.add(coverEntry);
        for (JsonNode pic : baseUpdates.path("pictures")) pictures.add(pic);

        ObjectNode copy = baseUpdates.deepCopy();
        copy.set("pictures", pictures);
        return copy;
    }

    private static List<String> nonUpdatableFields(JsonNode respData) {
        List<String> fields = new ArrayList<>();
        JsonNode cause = respData == null ? null : respData.get("cause");
        if (cause == null || cause.isNull()) return fields;
        List<JsonNode> causes = new ArrayList<>();
        if (cause.isArray()) {
            cause.forEach(causes::add);
        } else {
            causes.add(cause);
        }
        for (JsonNode c : causes) {
            if (!"field_not_updatable".equals(c.path("code").asText())) continue;
            JsonNode refs = c.get("references");
            if (refs == null || refs.isNull()) continue;
            if (refs.isArray()) {
                refs.forEach(r -> fields.add(r.asText()));
            } else {
                fields.add(refs.asText());
            }
        }
        return fields;
    }

    private void saveLog(String type, List<String> itemIds, JsonNode payload, Object response,
                         Object errors, Long userId, String batchId) {
        String status = statusFrom(response, errors);
        String errorMessage = null;
        if (errors instanceof List<?> list && !list.isEmpty()) {
            errorMessage = String.valueOf(list);
        } else if (errors != null && !(errors instanceof List<?>)) {
            errorMessage = String.valueOf(errors);
        }
        OperationLog logEntry = new OperationLog(type, itemIds, payload,
                MAPPER.valueToTree(response), status, errorMessage);
        logEntry.setUserId(userId);
        logEntry.setBatchId(batchId);
        logs.save(logEntry);
    }

    private static String statusFrom(Object response, Object errorsObj) {
        List<?> errs = errorsObj instanceof List<?> l ? l : List.of();
        if (errs.isEmpty()) return "success";
        int success = 0;
        if (response instanceof Map<?, ?> m && m.get("success") instanceof Number n) {
            success = n.intValue();
        }
        return success > 0 ? "partial" : "error";
    }

    private static void recordPutOutcome(Map<String, Object> result, String itemId, PutOutcome outcome) {
        if (outcome.ok()) {
            incrementSuccess(result);
            if (!outcome.skippedFields().isEmpty()) {
                warnings(result).add(Map.of("item_id", itemId, "skipped_fields", outcome.skippedFields()));
            }
        } else {
            errors(result).add(errorMap(itemId, outcome.error()));
        }
    }

    private static Map<String, Object> newBulkResult(int total) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("success", 0);
        out.put("errors", new ArrayList<Map<String, Object>>());
        out.put("warnings", new ArrayList<Map<String, Object>>());
        return out;
    }

    private static Map<String, Object> newAccountResult(long userId, int total) {
        Map<String, Object> out = newBulkResult(total);
        out.put("user_id", userId);
        return out;
    }

    private static void incrementSuccess(Map<String, Object> result) {
        result.put("success", ((Number) result.get("success")).intValue() + 1);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> errors(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("errors");
    }

    private static List<Map<String, Object>> accountErrors(Map<String, Object> result) {
        return errors(result);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> warnings(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("warnings");
    }

    private static Map<String, Object> errorMap(String itemId, Object error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("item_id", itemId);
        m.put("error", error);
        return m;
    }

    private List<Map<String, Object>> enrichGroups(List<Map<String, Object>> groups,
                                                   Map<String, String> titles, JsonNode before) {
        Map<Long, String> nickByUid = new LinkedHashMap<>();
        for (Map<String, Object> account : auth.listAccounts()) {
            nickByUid.put(number(account.get("user_id")).longValue(), String.valueOf(account.get("nickname")));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> g : groups) {
            long userId = number(g.get("user_id")).longValue();
            List<String> itemIds = strings(g.get("item_ids"));
            List<Map<String, Object>> items = new ArrayList<>();
            for (String itemId : itemIds) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", itemId);
                item.put("title", titles == null ? null : titles.get(itemId));
                item.put("before", before == null ? null : before.path(itemId));
                items.add(item);
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("user_id", userId);
            entry.put("nickname", nickByUid.getOrDefault(userId, "Conta " + userId));
            entry.put("item_ids", itemIds);
            entry.put("items", items);
            out.add(entry);
        }
        return out;
    }

    private static List<Map<String, Object>> sortedByCount(Map<String, Map<String, Object>> skuMap) {
        List<Map<String, Object>> out = new ArrayList<>(skuMap.values());
        out.sort((a, b) -> Integer.compare(((Number) b.get("count")).intValue(),
                ((Number) a.get("count")).intValue()));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void addPreview(Map<String, Object> entry, JsonNode item, Map<String, Object> account) {
        List<Map<String, Object>> preview = (List<Map<String, Object>>) entry.get("items_preview");
        if (preview.size() >= 3) return;
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", item.path("id").asText());
        p.put("title", item.path("title").asText(""));
        p.put("price", item.path("price").asDouble(0));
        p.put("thumbnail", item.path("thumbnail").asText(""));
        p.put("status", item.path("status").asText(""));
        p.put("listing_type_id", item.path("listing_type_id").asText(""));
        if (account != null) {
            p.put("user_id", account.get("user_id"));
            p.put("nickname", account.get("nickname"));
        }
        preview.add(p);
    }

    private List<String> searchItemIdsBySku(long userId, String paramName, String sku) {
        MeliResponse resp = client.get("/users/" + userId + "/items/search",
                Map.of(paramName, sku, "limit", "50"), userId);
        List<String> ids = new ArrayList<>();
        JsonNode results = resp.data() == null ? null : resp.data().path("results");
        if (results != null && results.isArray()) results.forEach(n -> ids.add(n.asText()));
        return ids;
    }

    private static String extractSku(JsonNode item) {
        JsonNode attrs = item.path("attributes");
        if (attrs.isArray()) {
            for (JsonNode attr : attrs) {
                if ("SELLER_SKU".equals(attr.path("id").asText()) && attr.hasNonNull("value_name")) {
                    return attr.path("value_name").asText();
                }
            }
        }
        return item.hasNonNull("seller_custom_field") ? item.path("seller_custom_field").asText() : null;
    }

    private static ObjectNode withoutKey(ObjectNode node, String key) {
        ObjectNode copy = node.deepCopy();
        copy.remove(key);
        return copy;
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof Iterable<?> it) {
            for (Object o : it) if (o != null) out.add(String.valueOf(o));
        }
        return out;
    }

    private static Number number(Object value) {
        if (value instanceof Number n) return n;
        return Long.parseLong(String.valueOf(value));
    }

    private static Object nullIfMissing(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node;
    }

    private static List<String> iterableKeys(ObjectNode node) {
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }
}
