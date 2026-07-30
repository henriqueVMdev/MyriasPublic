package com.hrb.mlmanager.meli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hrb.mlmanager.meli.MeliClient.MeliResponse;
import com.hrb.mlmanager.ops.OperationLog;
import com.hrb.mlmanager.ops.OperationLogRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
    private static final String CARS_DOMAIN_ID = "MLB-CARS_AND_VANS";
    private static final List<String> POSITION_HINTS = List.of(
            "POSIT", "SIDE", "AXIS", "INSTALLATION", "LOCATION");
    private static final List<String> POSITION_EXCLUDE = List.of("IMPACT", "POSITIVE_IMPACT");
    private static final int COMPAT_BATCH = 200;
    private static final int POSITION_BATCH = 100;
    private static final Pattern ITEM_ID_QUERY = Pattern.compile("ITEM_ID[=:]?(MLB\\d+)");
    private static final Pattern ITEM_ID_PATTERN = Pattern.compile("(?<!U)(MLB\\d+)");
    private static final Pattern MLBU_PATTERN = Pattern.compile("(MLBU\\d+)");
    private static final HttpClient SCRAPE_HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

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

    /** Fotos atuais de um anúncio (id + url, em ordem) — usado pela tool da IA. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getItemPictures(long userId, String itemId) {
        MeliResponse resp = client.get("/items/" + itemId, Map.of("attributes", "pictures"), userId);
        List<Map<String, Object>> out = new ArrayList<>();
        JsonNode pics = resp.data() == null ? null : resp.data().path("pictures");
        if (pics != null && pics.isArray()) {
            for (JsonNode p : pics) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", p.path("id").asText());
                m.put("url", p.path("secure_url").asText(p.path("url").asText("")));
                out.add(m);
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<JsonNode> getItemsBySku(long userId, String sku) {
        // União das duas buscas: seller_sku indexa o seller_custom_field e sku o
        // atributo SELLER_SKU — usar uma só (ou fallback) deixa anúncios de fora.
        LinkedHashSet<String> itemIds = new LinkedHashSet<>();
        itemIds.addAll(searchItemIdsBySku(userId, "seller_sku", sku));
        itemIds.addAll(searchItemIdsBySku(userId, "sku", sku));
        if (itemIds.isEmpty()) return List.of();
        return client.multiGetItems(new ArrayList<>(itemIds), ITEM_FIELDS, userId);
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
        MeliResponse resp = client.get("/categories/" + categoryId + "/attributes", userId);
        List<Map<String, Object>> out = new ArrayList<>();
        JsonNode attrs = resp.data();
        if (attrs == null || !attrs.isArray()) return out;
        for (JsonNode attr : attrs) {
            String id = attr.path("id").asText("").toUpperCase();
            if (!isPositionAttrId(id)) continue;
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

    // ---- Compatibilidades / posicoes ---------------------------------------

    @Transactional(readOnly = true)
    public Map<String, Object> getCompatibilitiesFromRef(String ref) {
        String itemId = extractItemId(ref);
        if (itemId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Nao foi possivel extrair um item_id MLB/MLBU da URL ou ID informado.");
        }

        List<Long> accountIds = new ArrayList<>();
        for (Map<String, Object> account : auth.listAccounts()) {
            accountIds.add(number(account.get("user_id")).longValue());
        }

        if (itemId.startsWith("MLBU")) {
            List<Long> users = new ArrayList<>();
            users.add(null);
            users.addAll(accountIds);
            for (Long uid : users) {
                try {
                    List<Map<String, Object>> compats = fetchCompatibilitiesViaCatalogProduct(itemId, uid);
                    if (!compats.isEmpty()) return compatResult(itemId, compats);
                } catch (Exception e) {
                    log.warn("Catalog product {} falhou user={}: {}", itemId, uid, e.getMessage());
                }
            }
        }

        try {
            List<Map<String, Object>> compats = fetchCompatibilitiesViaScrape(ref.startsWith("http") ? ref : itemId);
            if (!compats.isEmpty()) return compatResult(itemId, compats);
        } catch (Exception e) {
            log.warn("Scrape de compatibilidades falhou para {}: {}", ref, e.getMessage());
        }

        List<String> candidates = itemId.startsWith("MLBU") ? resolveMlbuToMlb(itemId) : List.of(itemId);
        List<Long> users = new ArrayList<>();
        users.add(null);
        users.addAll(accountIds);
        for (String candidate : candidates) {
            for (Long uid : users) {
                try {
                    List<Map<String, Object>> compats = fetchCompatibilitiesViaItem(candidate, uid);
                    if (!compats.isEmpty()) return compatResult(candidate, compats);
                } catch (Exception ignored) {
                    // tenta o proximo fallback
                }
                try {
                    List<Map<String, Object>> compats = fetchCompatibilitiesViaSubresource(candidate, uid);
                    if (!compats.isEmpty()) return compatResult(candidate, compats);
                } catch (Exception ignored) {
                    // tenta o proximo usuario/candidato
                }
            }
        }

        return compatResult(itemId, List.of());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getItemCompatibilitiesWithPositions(String itemId, long userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("compatibilities", getItemCompatibilities(itemId, userId));
        out.put("positions", extractItemPositions(itemId, userId));
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getItemCompatibilities(String itemId, Long userId) {
        if (userId != null) {
            try {
                String upId = getUserProductId(itemId, userId);
                if (upId != null) {
                    MeliResponse resp = client.get("/user-products/" + upId + "/compatibilities",
                            Map.of("include_restrictions", "true"), userId);
                    List<Map<String, Object>> compats = parseCompatibilities(resp.data());
                    log.info("compat user-product:{} item={} status={} found={}",
                            upId, itemId, resp.status(), compats.size());
                    if (!compats.isEmpty()) return compats;
                }
            } catch (Exception e) {
                log.warn("Falha lendo compat via user-product de {}: {}", itemId, e.getMessage());
            }
        }

        try {
            List<Map<String, Object>> compats = fetchCompatibilitiesViaSubresource(itemId, userId);
            if (!compats.isEmpty()) return compats;
        } catch (Exception e) {
            log.warn("Falha no subresource de compat {}: {}", itemId, e.getMessage());
        }

        try {
            return fetchCompatibilitiesViaItem(itemId, userId);
        } catch (Exception e) {
            log.warn("Falha lendo compat em /items/{}: {}", itemId, e.getMessage());
            return List.of();
        }
    }

    @Transactional
    public Map<String, Object> bulkUpdateCompatibilities(List<Map<String, Object>> groups,
                                                         List<String> productIds,
                                                         String mode,
                                                         String sku,
                                                         List<String> vehicleNames,
                                                         List<String> notes,
                                                         List<Map<String, Object>> positions,
                                                         String batchId) {
        if (groups.isEmpty() || productIds == null || productIds.isEmpty()) {
            Map<String, Object> out = newBulkResult(0);
            out.put("accounts", List.of());
            return out;
        }
        String normalizedMode = "append".equalsIgnoreCase(String.valueOf(mode)) ? "append" : "replace";
        List<Map<String, Object>> cleanPositions = cleanCompatPositions(positions);

        List<Map<String, Object>> products = new ArrayList<>();
        for (int i = 0; i < productIds.size(); i++) {
            String pid = productIds.get(i);
            if (pid == null || pid.isBlank()) continue;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("product_id", pid);
            String note = notes != null && i < notes.size() ? notes.get(i) : null;
            p.put("note", note == null || note.isBlank() ? null : note.strip());
            p.put("positions", cleanPositions);
            products.add(p);
        }

        List<Map<String, Object>> perAccount = new ArrayList<>();
        for (Map<String, Object> group : groups) {
            long userId = number(group.get("user_id")).longValue();
            List<String> itemIds = strings(group.get("item_ids"));
            Map<String, Object> per = newAccountResult(userId, itemIds.size());
            Map<String, List<String>> upsToItems = new LinkedHashMap<>();
            List<String> standalone = new ArrayList<>();
            Map<String, String> upByItem = getUserProductIds(itemIds, userId);
            for (String itemId : itemIds) {
                String upId = upByItem.get(itemId);
                if (upId == null || upId.isBlank()) {
                    standalone.add(itemId);
                } else {
                    upsToItems.computeIfAbsent(upId, k -> new ArrayList<>()).add(itemId);
                }
            }

            for (Map.Entry<String, List<String>> entry : upsToItems.entrySet()) {
                List<String> sharingItems = entry.getValue();
                String anchor = sharingItems.get(0);
                try {
                    applyCompatibilitiesToItem(anchor, userId, products, normalizedMode);
                    for (int i = 0; i < sharingItems.size(); i++) incrementSuccess(per);
                } catch (Exception e) {
                    for (String itemId : sharingItems) {
                        accountErrors(per).add(errorMap(itemId, e.getMessage()));
                    }
                }
            }

            for (String itemId : standalone) {
                try {
                    applyCompatibilitiesToItem(itemId, userId, products, normalizedMode);
                    incrementSuccess(per);
                } catch (Exception e) {
                    accountErrors(per).add(errorMap(itemId, e.getMessage()));
                }
            }
            perAccount.add(per);
        }

        Map<String, Object> out = finishGroupedResult(perAccount);
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("sku", sku);
        payload.put("mode", normalizedMode);
        payload.set("product_ids", MAPPER.valueToTree(productIds));
        payload.set("vehicle_names", MAPPER.valueToTree(vehicleNames == null ? List.of() : vehicleNames));
        payload.set("positions", MAPPER.valueToTree(cleanPositions));
        payload.set("groups", MAPPER.valueToTree(groupsWithNicknames(groups)));
        saveLog("bulk_update_compatibilities", collectAllItemIds(groups), payload,
                Map.of("per_account", perAccount, "total", out.get("total"), "success", out.get("success")),
                out.get("errors"), null, batchId);
        return out;
    }

    @Transactional
    public Map<String, Object> bulkUpdatePositions(List<Map<String, Object>> groups,
                                                   List<Map<String, Object>> positions,
                                                   String sku,
                                                   String batchId) {
        if (groups.isEmpty() || positions == null || positions.isEmpty()) {
            Map<String, Object> out = newBulkResult(0);
            out.put("accounts", List.of());
            return out;
        }

        List<Map<String, Object>> mlValues = normalizePositionValues(positions);
        if (mlValues.isEmpty()) {
            Map<String, Object> out = newBulkResult(0);
            out.put("accounts", List.of());
            return out;
        }
        List<Map<String, Object>> restrictions = List.of(Map.of(
                "attribute_id", "POSITION",
                "attribute_values", List.of(Map.of("values", mlValues))));

        List<Map<String, Object>> perAccount = new ArrayList<>();
        for (Map<String, Object> group : groups) {
            long userId = number(group.get("user_id")).longValue();
            List<String> itemIds = strings(group.get("item_ids"));
            Map<String, Object> per = newAccountResult(userId, itemIds.size());
            Map<String, String> upByItem = getUserProductIds(itemIds, userId);
            Map<String, List<String>> upsToItems = new LinkedHashMap<>();
            List<String> noUp = new ArrayList<>();
            for (String itemId : itemIds) {
                String upId = upByItem.get(itemId);
                if (upId == null || upId.isBlank()) {
                    noUp.add(itemId);
                } else {
                    upsToItems.computeIfAbsent(upId, k -> new ArrayList<>()).add(itemId);
                }
            }

            for (Map.Entry<String, List<String>> entry : upsToItems.entrySet()) {
                try {
                    applyPositionsToUserProduct(entry.getKey(), userId, restrictions);
                    for (int i = 0; i < entry.getValue().size(); i++) incrementSuccess(per);
                } catch (Exception e) {
                    for (String itemId : entry.getValue()) {
                        accountErrors(per).add(errorMap(itemId, e.getMessage()));
                    }
                }
            }
            for (String itemId : noUp) {
                accountErrors(per).add(errorMap(itemId,
                        "Item sem user_product_id; edicao de posicao nao suportada."));
            }
            perAccount.add(per);
        }

        Map<String, Object> out = finishGroupedResult(perAccount);
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("sku", sku);
        payload.set("positions", MAPPER.valueToTree(positions));
        payload.set("groups", MAPPER.valueToTree(groups));
        saveLog("bulk_update_positions", collectAllItemIds(groups), payload,
                Map.of("per_account", perAccount, "total", out.get("total"), "success", out.get("success")),
                out.get("errors"), null, batchId);
        return out;
    }

    // ---- Internos -----------------------------------------------------------

    private static Map<String, Object> compatResult(String itemId, List<Map<String, Object>> compats) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("item_id", itemId);
        out.put("compatibilities", compats);
        return out;
    }

    private List<Map<String, Object>> fetchCompatibilitiesViaItem(String itemId, Long userId) {
        MeliResponse resp = userId == null
                ? client.getPublic("/items/" + itemId)
                : client.get("/items/" + itemId, userId);
        JsonNode body = resp.data();
        List<Map<String, Object>> compats = parseCompatibilities(body == null ? null : body.get("compatibilities"));
        log.info("compat /items/{} user={} status={} found={}", itemId, userId, resp.status(), compats.size());
        return compats;
    }

    private List<Map<String, Object>> fetchCompatibilitiesViaSubresource(String itemId, Long userId) {
        String path = "/items/" + itemId + "/compatibilities";
        MeliResponse resp = userId == null ? client.getPublic(path) : client.get(path, userId);
        List<Map<String, Object>> compats = parseCompatibilities(resp.data());
        log.info("compat {} user={} status={} found={}", path, userId, resp.status(), compats.size());
        return compats;
    }

    private List<Map<String, Object>> fetchCompatibilitiesViaCatalogProduct(String productId, Long userId) {
        String path = "/products/" + productId;
        MeliResponse resp = userId == null ? client.getPublic(path) : client.get(path, userId);
        JsonNode body = resp.data();
        if (body == null) return List.of();

        List<Map<String, Object>> direct = parseCompatibilities(body.get("compatibilities"));
        if (!direct.isEmpty()) return direct;

        JsonNode attrs = body.path("attributes");
        if (attrs.isArray()) {
            for (JsonNode attr : attrs) {
                String id = attr.path("id").asText("");
                if (!List.of("COMPATIBLE_VEHICLES", "COMPATIBILITIES", "VEHICLES").contains(id)) continue;
                List<Map<String, Object>> fromValues = parseCompatibilities(attr.get("values"));
                if (!fromValues.isEmpty()) return fromValues;
                List<Map<String, Object>> fromStruct = parseCompatibilities(attr.get("value_struct"));
                if (!fromStruct.isEmpty()) return fromStruct;
            }
        }
        return List.of();
    }

    private List<Map<String, Object>> fetchCompatibilitiesViaScrape(String itemIdOrRef) {
        String url = itemIdOrRef;
        if (!url.startsWith("http")) {
            url = itemIdOrRef.startsWith("MLBU")
                    ? "https://www.mercadolivre.com.br/noindex/up/" + itemIdOrRef
                    : "https://www.mercadolivre.com.br/noindex/item/" + itemIdOrRef;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(15))
                    .header("User-Agent", "Googlebot/2.1 (+http://www.google.com/bot.html)")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "pt-BR,pt;q=0.9")
                    .GET()
                    .build();
            HttpResponse<String> response = SCRAPE_HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.info("scrape compat {} status={}", url, response.statusCode());
                return List.of();
            }
            String html = response.body();
            Map<String, Map<String, Object>> found = new LinkedHashMap<>();
            collectScrapedCompats(html, found,
                    Pattern.compile("\"domain_id\"\\s*:\\s*\"(MLB-[A-Z_]+)\"[^{}]{0,300}?\"(?:catalog_)?product_id\"\\s*:\\s*\"(MLB\\d+)\"",
                            Pattern.CASE_INSENSITIVE | Pattern.DOTALL));
            collectScrapedCompats(html, found,
                    Pattern.compile("\"(?:catalog_)?product_id\"\\s*:\\s*\"(MLB\\d+)\"[^{}]{0,300}?\"domain_id\"\\s*:\\s*\"(MLB-[A-Z_]+)\"",
                            Pattern.CASE_INSENSITIVE | Pattern.DOTALL));
            collectScrapedCompats(html, found,
                    Pattern.compile("\"productId\"\\s*:\\s*\"(MLB\\d+)\"[^{}]{0,300}?\"domainId\"\\s*:\\s*\"(MLB-[A-Z_]+)\"",
                            Pattern.CASE_INSENSITIVE | Pattern.DOTALL));
            collectScrapedCompats(html, found,
                    Pattern.compile("\"domainId\"\\s*:\\s*\"(MLB-[A-Z_]+)\"[^{}]{0,300}?\"productId\"\\s*:\\s*\"(MLB\\d+)\"",
                            Pattern.CASE_INSENSITIVE | Pattern.DOTALL));
            log.info("scrape compat {} found={}", url, found.size());
            return new ArrayList<>(found.values());
        } catch (Exception e) {
            log.warn("scrape compat {} falhou: {}", url, e.getMessage());
            return List.of();
        }
    }

    private static void collectScrapedCompats(String html, Map<String, Map<String, Object>> found, Pattern pattern) {
        Matcher matcher = pattern.matcher(html);
        while (matcher.find()) {
            String a = matcher.group(1);
            String b = matcher.group(2);
            String productId = a != null && a.matches("MLB\\d+") ? a : b;
            String domainId = a != null && a.startsWith("MLB-") ? a : (b != null && b.startsWith("MLB-") ? b : CARS_DOMAIN_ID);
            if (productId == null || !productId.matches("MLB\\d+") || found.containsKey(productId)) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("product_id", productId);
            entry.put("domain_id", domainId);
            entry.put("name", "");
            entry.put("attributes", List.of());
            entry.put("note", null);
            found.put(productId, entry);
        }
    }

    private List<String> resolveMlbuToMlb(String mlbuId) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://www.mercadolivre.com.br/noindex/up/" + mlbuId))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .GET()
                    .build();
            HttpResponse<String> response = SCRAPE_HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            Matcher matcher = Pattern.compile("MLB[-]?(\\d{8,})").matcher(response.body());
            Set<String> ids = new LinkedHashSet<>();
            while (matcher.find()) ids.add("MLB" + matcher.group(1));
            return new ArrayList<>(ids);
        } catch (Exception e) {
            log.warn("resolve MLBU {} falhou: {}", mlbuId, e.getMessage());
            return List.of();
        }
    }

    private static List<Map<String, Object>> parseCompatibilities(JsonNode payload) {
        if (payload == null || payload.isMissingNode() || payload.isNull()) return List.of();
        JsonNode items = null;
        if (payload.isArray()) {
            items = payload;
        } else if (payload.isObject()) {
            for (String key : List.of("products", "compatibilities", "results")) {
                JsonNode candidate = payload.get(key);
                if (candidate != null && candidate.isArray()) {
                    items = candidate;
                    break;
                }
            }
        }
        if (items == null || !items.isArray()) return List.of();

        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode raw : items) {
            if (!raw.isObject()) continue;
            String pid = firstText(raw, "catalog_product_id", "product_id");
            String rawId = text(raw, "id");
            if ((pid == null || pid.isBlank()) && rawId != null && rawId.matches("MLB\\d+")) {
                pid = rawId;
            }
            if (pid == null || pid.isBlank()) continue;

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("product_id", pid);
            entry.put("domain_id", firstTextOrDefault(raw, CARS_DOMAIN_ID, "domain_id", "domainId"));
            entry.put("name", firstTextOrDefault(raw, "", "catalog_product_name", "name", "title"));
            JsonNode attrs = raw.get("attributes");
            entry.put("attributes", attrs != null && attrs.isArray() ? attrs : List.of());
            String note = text(raw, "note");
            entry.put("note", note == null || note.isBlank() ? null : note.strip());
            if (rawId != null && !rawId.equals(pid)) entry.put("compatibility_id", rawId);
            out.add(entry);
        }
        return out;
    }

    private List<Map<String, Object>> extractItemPositions(String itemId, long userId) {
        List<Map<String, Object>> positions = new ArrayList<>();
        try {
            MeliResponse itemResp = client.get("/items/" + itemId, userId);
            JsonNode item = itemResp.data();
            String upId = item == null ? null : item.path("user_product_id").asText(null);
            if (upId != null && !upId.isBlank()) {
                MeliResponse upResp = client.get("/user-products/" + upId, userId);
                JsonNode attrs = upResp.data() == null ? null : upResp.data().path("attributes");
                if (attrs != null && attrs.isArray()) {
                    for (JsonNode attr : attrs) {
                        if (!isPositionAttrId(attr.path("id").asText("").toUpperCase())) continue;
                        JsonNode values = attr.path("values");
                        if (!values.isArray()) continue;
                        for (JsonNode v : values) {
                            String valueId = text(v, "id");
                            if (valueId == null || valueId.equals("-1")) continue;
                            Map<String, Object> pos = new LinkedHashMap<>();
                            pos.put("id", attr.path("id").asText());
                            pos.put("value_id", valueId);
                            pos.put("value_name", text(v, "name"));
                            positions.add(pos);
                        }
                    }
                }
            } else if (item != null && item.path("attributes").isArray()) {
                for (JsonNode attr : item.path("attributes")) {
                    if (!isPositionAttrId(attr.path("id").asText("").toUpperCase())) continue;
                    String valueId = text(attr, "value_id");
                    if (valueId == null || valueId.equals("-1")) continue;
                    Map<String, Object> pos = new LinkedHashMap<>();
                    pos.put("id", attr.path("id").asText());
                    pos.put("value_id", valueId);
                    pos.put("value_name", text(attr, "value_name"));
                    positions.add(pos);
                }
            }
        } catch (Exception e) {
            log.warn("Falha lendo posicoes de {}: {}", itemId, e.getMessage());
        }
        return positions;
    }

    private void applyCompatibilitiesToItem(String itemId, long userId, List<Map<String, Object>> products, String mode) {
        String upId = getUserProductId(itemId, userId);
        String basePath = upId == null
                ? "/items/" + itemId + "/compatibilities"
                : "/user-products/" + upId + "/compatibilities";
        String scope = upId == null ? "item:" + itemId : "user-product:" + upId;

        Map<String, Map<String, Object>> target = new LinkedHashMap<>();
        for (Map<String, Object> product : products) {
            String pid = String.valueOf(product.getOrDefault("product_id", "")).strip();
            if (!pid.isBlank()) target.put(pid, product);
        }
        boolean hasPositions = target.values().stream().anyMatch(p ->
                p.get("positions") instanceof List<?> l && !l.isEmpty());

        Map<String, String> currentNotes = new LinkedHashMap<>();
        for (Map<String, Object> c : getItemCompatibilities(itemId, userId)) {
            Object pidObj = c.get("product_id");
            if (pidObj == null) continue;
            String note = c.get("note") == null ? null : String.valueOf(c.get("note")).strip();
            currentNotes.put(String.valueOf(pidObj), note == null || note.isBlank() ? null : note);
        }

        Set<String> toDelete = new LinkedHashSet<>();
        List<Map<String, Object>> toAdd = new ArrayList<>();
        if ("replace".equals(mode)) {
            for (Map.Entry<String, String> current : currentNotes.entrySet()) {
                Map<String, Object> wanted = target.get(current.getKey());
                if (wanted == null || !sameNote(wanted.get("note"), current.getValue()) || hasPositions) {
                    toDelete.add(current.getKey());
                }
            }
            for (Map.Entry<String, Map<String, Object>> wanted : target.entrySet()) {
                String currentNote = currentNotes.get(wanted.getKey());
                if (!currentNotes.containsKey(wanted.getKey()) || !sameNote(wanted.getValue().get("note"), currentNote)
                        || hasPositions) {
                    toAdd.add(buildCompatPostEntry(wanted.getKey(), wanted.getValue()));
                }
            }
        } else {
            for (Map.Entry<String, Map<String, Object>> wanted : target.entrySet()) {
                if (!currentNotes.containsKey(wanted.getKey())) {
                    toAdd.add(buildCompatPostEntry(wanted.getKey(), wanted.getValue()));
                }
            }
        }

        List<String> deleteList = new ArrayList<>(toDelete);
        for (int i = 0; i < deleteList.size(); i += COMPAT_BATCH) {
            List<String> chunk = deleteList.subList(i, Math.min(i + COMPAT_BATCH, deleteList.size()));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("main_domain_id", CARS_DOMAIN_ID);
            payload.put("products", chunk.stream().map(pid -> Map.of("id", pid)).toList());
            MeliResponse resp = client.delete(basePath, userId, payload);
            if (resp.status() != 200 && resp.status() != 204) {
                throw new IllegalStateException("Bulk DELETE rejeitado em " + scope + ": "
                        + resp.status() + " " + resp.data());
            }
        }

        for (int i = 0; i < toAdd.size(); i += COMPAT_BATCH) {
            List<Map<String, Object>> chunk = toAdd.subList(i, Math.min(i + COMPAT_BATCH, toAdd.size()));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("domain_id", CARS_DOMAIN_ID);
            payload.put("products", chunk);
            MeliResponse resp = client.post(basePath, userId, payload);
            if (resp.status() != 200 && resp.status() != 201) {
                throw new IllegalStateException("ML rejeitou POST em " + scope + ": "
                        + resp.status() + " " + resp.data());
            }
        }
        log.info("apply compat {} mode={} -{} +{}", scope, mode, deleteList.size(), toAdd.size());
    }

    private void applyPositionsToUserProduct(String upId, long userId, List<Map<String, Object>> restrictions) {
        MeliResponse resp = client.get("/user-products/" + upId + "/compatibilities",
                Map.of("main_domain_id", CARS_DOMAIN_ID), userId);
        JsonNode products = resp.data() == null ? null : resp.data().path("products");
        Set<String> catalogIds = new LinkedHashSet<>();
        if (products != null && products.isArray()) {
            for (JsonNode p : products) {
                String pid = firstText(p, "catalog_product_id", "product_id", "id");
                if (pid != null && pid.matches("MLB\\d+")) catalogIds.add(pid);
            }
        }
        if (catalogIds.isEmpty()) {
            throw new IllegalStateException("user-product sem veiculos compativeis");
        }

        List<String> ids = new ArrayList<>(catalogIds);
        for (int i = 0; i < ids.size(); i += POSITION_BATCH) {
            List<String> chunk = ids.subList(i, Math.min(i + POSITION_BATCH, ids.size()));
            List<Map<String, Object>> updateProducts = new ArrayList<>();
            for (String pid : chunk) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", pid);
                entry.put("restrictions", restrictions);
                updateProducts.add(entry);
            }
            Map<String, Object> update = new LinkedHashMap<>();
            update.put("products", updateProducts);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("domain_id", CARS_DOMAIN_ID);
            payload.put("update", update);
            MeliResponse put = client.put("/user-products/" + upId + "/compatibilities", userId, payload);
            if (put.status() != 200 && put.status() != 201) {
                throw new IllegalStateException("PUT user-product compat " + upId + " rejeitado: "
                        + put.status() + " " + put.data());
            }
        }
    }

    private String getUserProductId(String itemId, long userId) {
        try {
            MeliResponse resp = client.get("/items/" + itemId, Map.of("attributes", "user_product_id"), userId);
            JsonNode body = resp.data();
            String upId = body == null ? null : body.path("user_product_id").asText(null);
            return upId == null || upId.isBlank() ? null : upId;
        } catch (Exception e) {
            log.warn("Nao consegui ler user_product_id de {}: {}", itemId, e.getMessage());
            return null;
        }
    }

    private Map<String, String> getUserProductIds(List<String> itemIds, long userId) {
        Map<String, String> out = new LinkedHashMap<>();
        if (itemIds == null || itemIds.isEmpty()) return out;
        try {
            for (JsonNode item : client.multiGetItems(itemIds, List.of("id", "user_product_id"), userId)) {
                String id = item.path("id").asText(null);
                String upId = item.path("user_product_id").asText(null);
                if (id != null && upId != null && !upId.isBlank()) out.put(id, upId);
            }
        } catch (Exception e) {
            log.warn("multiGet user_product_id falhou user={}: {}", userId, e.getMessage());
        }
        return out;
    }

    private static List<Map<String, Object>> cleanCompatPositions(List<Map<String, Object>> positions) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (positions == null) return out;
        for (Map<String, Object> p : positions) {
            if (p == null) continue;
            Object idObj = p.getOrDefault("id", p.get("attribute_id"));
            Object valueObj = p.get("value_id");
            if (idObj == null || valueObj == null) continue;
            Map<String, Object> clean = new LinkedHashMap<>();
            clean.put("id", String.valueOf(idObj));
            clean.put("value_id", String.valueOf(valueObj));
            out.add(clean);
        }
        return out;
    }

    private static List<Map<String, Object>> normalizePositionValues(List<Map<String, Object>> positions) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> p : positions) {
            PositionValue value = resolvePositionValue(p);
            if (value == null || !seen.add(value.id())) continue;
            out.add(Map.of("value_id", value.id(), "value_name", value.name()));
        }
        return out;
    }

    private record PositionValue(String id, String name) {}

    private static PositionValue resolvePositionValue(Map<String, Object> p) {
        String name = p.get("value_name") == null ? "" : String.valueOf(p.get("value_name"));
        String key = name.toLowerCase(Locale.ROOT).strip();
        String stem = key.replaceAll("[aeiou]$", "");
        if (stem.endsWith("direit")) return new PositionValue("2262160", "Direita");
        if (stem.endsWith("esquerd")) return new PositionValue("2262158", "Esquerda");
        if (stem.endsWith("dianteir")) return new PositionValue("13701104", "Dianteira");
        if (stem.endsWith("traseir")) return new PositionValue("13701105", "Traseira");

        String valueId = p.get("value_id") == null ? "" : String.valueOf(p.get("value_id"));
        return switch (valueId) {
            case "2262160" -> new PositionValue("2262160", "Direita");
            case "2262158" -> new PositionValue("2262158", "Esquerda");
            case "13701104" -> new PositionValue("13701104", "Dianteira");
            case "13701105" -> new PositionValue("13701105", "Traseira");
            default -> null;
        };
    }

    private static Map<String, Object> buildCompatPostEntry(String pid, Map<String, Object> info) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", pid);
        Object note = info.get("note");
        if (note != null && !String.valueOf(note).isBlank()) entry.put("note", String.valueOf(note).strip());
        Object positions = info.get("positions");
        if (positions instanceof List<?> l && !l.isEmpty()) entry.put("positions", positions);
        return entry;
    }

    private static boolean sameNote(Object wanted, String current) {
        String a = wanted == null ? null : String.valueOf(wanted).strip();
        String b = current == null ? null : current.strip();
        if (a != null && a.isBlank()) a = null;
        if (b != null && b.isBlank()) b = null;
        return java.util.Objects.equals(a, b);
    }

    private Map<String, Object> finishGroupedResult(List<Map<String, Object>> perAccount) {
        int total = perAccount.stream().mapToInt(a -> ((Number) a.get("total")).intValue()).sum();
        int success = perAccount.stream().mapToInt(a -> ((Number) a.get("success")).intValue()).sum();
        List<Map<String, Object>> allErrors = new ArrayList<>();
        for (Map<String, Object> a : perAccount) allErrors.addAll(accountErrors(a));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("success", success);
        out.put("errors", allErrors);
        out.put("accounts", perAccount);
        return out;
    }

    private List<Map<String, Object>> groupsWithNicknames(List<Map<String, Object>> groups) {
        Map<Long, String> nickByUid = new LinkedHashMap<>();
        for (Map<String, Object> account : auth.listAccounts()) {
            nickByUid.put(number(account.get("user_id")).longValue(), String.valueOf(account.get("nickname")));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> g : groups) {
            long userId = number(g.get("user_id")).longValue();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("user_id", userId);
            entry.put("nickname", nickByUid.getOrDefault(userId, "Conta " + userId));
            entry.put("item_ids", strings(g.get("item_ids")));
            out.add(entry);
        }
        return out;
    }

    private static List<String> collectAllItemIds(List<Map<String, Object>> groups) {
        List<String> allIds = new ArrayList<>();
        for (Map<String, Object> g : groups) allIds.addAll(strings(g.get("item_ids")));
        return allIds;
    }

    private static String extractItemId(String ref) {
        if (ref == null) return null;
        String raw = ref.strip();
        String cleaned = raw.toUpperCase(Locale.ROOT).replace("-", "");
        Matcher itemQuery = ITEM_ID_QUERY.matcher(cleaned);
        if (itemQuery.find()) return itemQuery.group(1);
        Matcher item = ITEM_ID_PATTERN.matcher(cleaned);
        if (item.find()) return item.group(1);
        Matcher mlbu = MLBU_PATTERN.matcher(cleaned);
        if (mlbu.find()) return mlbu.group(1);
        String simple = raw.toUpperCase(Locale.ROOT).strip().replace("/", "");
        return simple.matches("MLB\\d+") ? simple : null;
    }

    private static boolean isPositionAttrId(String attrIdUpper) {
        if (POSITION_EXCLUDE.stream().anyMatch(attrIdUpper::contains)) return false;
        return POSITION_HINTS.stream().anyMatch(attrIdUpper::contains);
    }

    private static String firstText(JsonNode node, String... keys) {
        if (node == null) return null;
        for (String key : keys) {
            String value = text(node, key);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String firstTextOrDefault(JsonNode node, String defaultValue, String... keys) {
        String value = firstText(node, keys);
        return value == null ? defaultValue : value;
    }

    private static String text(JsonNode node, String key) {
        if (node == null || key == null) return null;
        JsonNode value = node.get(key);
        if (value == null || value.isNull() || value.isMissingNode()) return null;
        return value.asText(null);
    }

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
        List<String> ids = new ArrayList<>();
        // ponytail: teto de 1000 ids por SKU; paginação real se algum dia passar disso
        for (int offset = 0; offset < 1000; offset += 50) {
            MeliResponse resp = client.get("/users/" + userId + "/items/search",
                    Map.of(paramName, sku, "limit", "50", "offset", String.valueOf(offset)), userId);
            JsonNode results = resp.data() == null ? null : resp.data().path("results");
            int before = ids.size();
            if (results != null && results.isArray()) results.forEach(n -> ids.add(n.asText()));
            if (ids.size() - before < 50) break;
        }
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
