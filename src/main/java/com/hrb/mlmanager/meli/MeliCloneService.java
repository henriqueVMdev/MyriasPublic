package com.hrb.mlmanager.meli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hrb.mlmanager.meli.MeliClient.MeliResponse;
import com.hrb.mlmanager.ops.OperationLog;
import com.hrb.mlmanager.ops.OperationLogRepository;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Clone de anuncios, portando o contrato de backend/app/api/clone.py. */
@Service
public class MeliCloneService {

    private static final Logger log = LoggerFactory.getLogger(MeliCloneService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CARS_DOMAIN_ID = "MLB-CARS_AND_VANS";
    private static final int COMPAT_BATCH = 200;
    private static final Set<String> SKIP_ATTR_IDS = Set.of("SELLER_SKU", "GTIN", "MPN", "ITEM_CONDITION");
    private static final Set<String> PACKAGE_LOWER_IDS = Set.of(
            "seller_package_height", "seller_package_width",
            "seller_package_length", "seller_package_weight");
    private static final Set<String> PACKAGE_ANY_IDS = Set.of(
            "PACKAGE_HEIGHT", "PACKAGE_WIDTH", "PACKAGE_LENGTH", "PACKAGE_WEIGHT",
            "SELLER_PACKAGE_HEIGHT", "SELLER_PACKAGE_WIDTH",
            "SELLER_PACKAGE_LENGTH", "SELLER_PACKAGE_WEIGHT",
            "seller_package_height", "seller_package_width",
            "seller_package_length", "seller_package_weight");
    private static final Set<String> FORM_SKIP_ATTR_IDS = Set.of(
            "PACKAGE_HEIGHT", "PACKAGE_WIDTH", "PACKAGE_LENGTH", "PACKAGE_WEIGHT",
            "SELLER_PACKAGE_HEIGHT", "SELLER_PACKAGE_WIDTH",
            "SELLER_PACKAGE_LENGTH", "SELLER_PACKAGE_WEIGHT", "SHIPMENT_PACKAGING");
    private static final Map<String, String> PACKAGE_ATTR_REMAP = Map.of(
            "PACKAGE_HEIGHT", "seller_package_height",
            "PACKAGE_WIDTH", "seller_package_width",
            "PACKAGE_LENGTH", "seller_package_length",
            "PACKAGE_WEIGHT", "seller_package_weight",
            "SELLER_PACKAGE_HEIGHT", "seller_package_height",
            "SELLER_PACKAGE_WIDTH", "seller_package_width",
            "SELLER_PACKAGE_LENGTH", "seller_package_length",
            "SELLER_PACKAGE_WEIGHT", "seller_package_weight");
    private static final Map<String, String> PACKAGE_ATTR_REMAP_INV = Map.of(
            "seller_package_height", "PACKAGE_HEIGHT",
            "seller_package_width", "PACKAGE_WIDTH",
            "seller_package_length", "PACKAGE_LENGTH",
            "seller_package_weight", "PACKAGE_WEIGHT");
    private static final Map<String, Map<String, Number>> DEFAULT_DIMS_BY_CATEGORY = Map.of(
            "MLB212730", Map.of("height", 10, "width", 20, "length", 40, "weight", 1500),
            "MLB47097", Map.of("height", 4, "width", 14, "length", 26, "weight", 200));
    private static final Map<String, Number> DEFAULT_DIMS_FALLBACK =
            Map.of("height", 10, "width", 20, "length", 30, "weight", 500);
    private static final Pattern ITEM_ID_QUERY = Pattern.compile("ITEM_ID[=:]?(MLB\\d+)");
    private static final Pattern ITEM_ID_PATTERN = Pattern.compile("(?<!U)(MLB\\d{8,})");
    private static final Pattern MLBU_PATTERN = Pattern.compile("(MLBU\\d+)");

    private final MeliClient client;
    private final MeliAuthService auth;
    private final MeliBulkService bulk;
    private final OperationLogRepository logs;

    public MeliCloneService(MeliClient client, MeliAuthService auth,
                            MeliBulkService bulk, OperationLogRepository logs) {
        this.client = client;
        this.auth = auth;
        this.bulk = bulk;
        this.logs = logs;
    }

    private record PreviewItem(ObjectNode item, String itemId, Long ownerUserId, boolean fromCatalog) {}
    private record PositionValue(String id, String name) {}

    @Transactional(readOnly = true)
    public Map<String, Object> preview(String itemIdOrUrl) {
        if (auth.listAccounts().isEmpty()) {
            throw new IllegalStateException(
                    "Nenhuma conta do Mercado Livre esta conectada. "
                    + "Conecte a conta novamente pelo botao \"Conectar Mercado Livre\" "
                    + "e tente copiar o anuncio de novo.");
        }
        List<String> candidateIds = extractItemIds(itemIdOrUrl);
        PreviewItem previewItem = fetchPreviewItem(candidateIds);
        if (previewItem == null) {
            throw new IllegalArgumentException("Nao foi possivel obter os dados deste anuncio.");
        }

        ObjectNode item = previewItem.item();
        String itemId = previewItem.itemId();
        String categoryId = item.path("category_id").asText("");

        String description = previewItem.fromCatalog() ? "" : fetchDescription(itemId, previewItem.ownerUserId());
        ArrayNode variations = previewItem.fromCatalog() ? MAPPER.createArrayNode() : fetchVariations(itemId, previewItem.ownerUserId());
        List<Map<String, Object>> compatibilities = previewItem.fromCatalog()
                ? List.of()
                : fetchCompatibilities(itemIdOrUrl, itemId, previewItem.ownerUserId());
        List<Map<String, Object>> positionRestrictions = previewItem.fromCatalog()
                ? List.of()
                : fetchPositionRestrictions(itemId, previewItem.ownerUserId(), item.path("title").asText(""));

        Set<String> blockedAttrs = getBlockedAttributeIds(categoryId);
        List<JsonNode> itemAttrs = copyArray(item.path("attributes"));
        boolean catalogHasSourceAttrs = itemAttrs.stream().anyMatch(attr ->
                hasAttrValue(attr) && !PACKAGE_ANY_IDS.contains(attr.path("id").asText("")));
        Map<String, Number> usedDefaultDims = injectPackageAttrs(itemAttrs, item, categoryId,
                previewItem.fromCatalog() ? null : previewItem.ownerUserId());
        List<Map<String, Object>> copyableAttrs = filterAttributes(itemAttrs, blockedAttrs);
        if (previewItem.fromCatalog() && !catalogHasSourceAttrs) {
            copyableAttrs = categoryAttributeForm(categoryId);
        }
        forcePackageAttrs(copyableAttrs, itemAttrs);

        Map<String, Object> original = new LinkedHashMap<>();
        original.put("id", item.path("id").asText(itemId));
        original.put("title", item.path("title").asText(""));
        original.put("price", item.path("price").asDouble(0));
        original.put("category_id", categoryId);
        original.put("condition", item.path("condition").asText("new"));
        original.put("pictures", jsonToObject(item.path("pictures")));
        original.put("attributes", jsonToObject(item.path("attributes")));
        original.put("seller_id", item.hasNonNull("seller_id") ? item.path("seller_id").asLong() : null);
        original.put("permalink", item.path("permalink").asText(""));
        original.put("available_quantity", item.path("available_quantity").asInt(0));
        original.put("sold_quantity", item.path("sold_quantity").asInt(0));
        original.put("status", item.path("status").asText(""));
        original.put("listing_type_id", item.path("listing_type_id").asText(""));
        original.put("shipping", jsonToObject(item.path("shipping")));
        original.put("sale_terms", jsonToObject(item.path("sale_terms")));
        original.put("variations", jsonToObject(variations));
        original.put("compatibilities", compatibilities);

        Map<String, Object> suggested = new LinkedHashMap<>();
        suggested.put("title", item.path("title").asText(""));
        suggested.put("category_id", categoryId);
        suggested.put("condition", item.path("condition").asText("new"));
        suggested.put("price", item.path("price").asDouble(0));
        suggested.put("available_quantity", 1);
        suggested.put("currency_id", item.path("currency_id").asText("BRL"));
        suggested.put("buying_mode", "buy_it_now");
        suggested.put("listing_type_id", "gold_special");
        suggested.put("pictures", pictureSources(item.path("pictures")));
        suggested.put("attributes", copyableAttrs);
        suggested.put("sale_terms", jsonToObject(item.path("sale_terms")));
        suggested.put("shipping", Map.of("local_pick_up", item.path("shipping").path("local_pick_up").asBoolean(false)));
        suggested.put("description", description);
        suggested.put("channels", List.of("marketplace"));
        suggested.put("compatibilities", compatibilities);
        suggested.put("position_restrictions", positionRestrictions);
        suggested.put("_source_item_id", itemId);
        suggested.put("_used_default_dims", usedDefaultDims);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("original", original);
        out.put("suggested", suggested);
        return out;
    }

    @Transactional
    public Map<String, Object> create(ObjectNode originalData, Long userId, String batchId) {
        ObjectNode data = originalData.deepCopy();
        String friendlyTitle = data.path("title").asText(null);
        String friendlyListingType = data.path("listing_type_id").asText(null);
        String description = textAndRemove(data, "description");
        ArrayNode compatibilities = arrayAndRemove(data, "compatibilities");
        ArrayNode positionRestrictions = arrayAndRemove(data, "position_restrictions");
        String sourceItemId = textAndRemove(data, "_source_item_id");
        JsonNode usedDefaultDims = data.remove("_used_default_dims");

        normalizeAttributesForCreate(data);
        String categoryId = data.path("category_id").asText(null);
        if (categoryId != null && !categoryId.isBlank()) ensureRequiredAttributes(data, categoryId);

        String sellerCustomField = textAndRemove(data, "seller_custom_field");
        MeliResponse createResp = client.post("/items", userId, data);
        if (createResp.status() != 200 && createResp.status() != 201 && mentionsFamilyName(createResp.data())) {
            if (data.hasNonNull("title")) {
                data.put("family_name", data.path("title").asText(""));
                data.remove("title");
                createResp = client.post("/items", userId, data);
            }
        }

        if (createResp.status() != 200 && createResp.status() != 201) {
            saveCloneLog(userId, batchId, null, friendlyTitle, friendlyListingType, sourceItemId,
                    sellerCustomField, data, createResp.data(), "error", String.valueOf(createResp.data()));
            throw new IllegalStateException(readableCreateError(createResp.data()));
        }

        ObjectNode newItem = createResp.data() != null && createResp.data().isObject()
                ? (ObjectNode) createResp.data().deepCopy()
                : MAPPER.createObjectNode();
        String newItemId = newItem.path("id").asText();
        if (sellerCustomField != null && !sellerCustomField.isBlank()) {
            applySku(newItemId, sellerCustomField, userId);
        }
        if (description != null && !description.isBlank()) {
            applyDescription(newItemId, description, userId);
        }

        int compatApplied = applyCompatibilities(newItem, newItemId, compatibilities,
                positionRestrictions, sourceItemId, userId);

        ObjectNode meta = MAPPER.createObjectNode();
        meta.put("compat_source_count", compatibilities.size());
        meta.put("compat_applied", compatApplied);
        meta.put("position_count", positionRestrictions.size());
        if (usedDefaultDims != null) meta.set("used_default_dims", usedDefaultDims);
        newItem.set("_clone_meta", meta);

        saveCloneLog(userId, batchId, newItemId, friendlyTitle, friendlyListingType, sourceItemId,
                sellerCustomField, data, newItem, "success", null);
        return MAPPER.convertValue(newItem, new TypeReference<>() {});
    }

    @Transactional
    public Map<String, Object> createMulti(ObjectNode data, List<Long> userIds, String batchId) {
        if (userIds == null || userIds.isEmpty()) {
            throw new IllegalArgumentException("Nenhuma conta selecionada");
        }
        List<Map<String, Object>> results = new ArrayList<>();
        int success = 0;
        for (Long userId : userIds) {
            try {
                Map<String, Object> item = create(data, userId, batchId);
                Map<String, Object> itemOut = new LinkedHashMap<>();
                itemOut.put("id", item.get("id"));
                itemOut.put("permalink", item.get("permalink"));
                itemOut.put("title", item.get("title"));
                results.add(Map.of("user_id", userId, "success", true, "item", itemOut));
                success++;
            } catch (Exception e) {
                log.warn("Falha clone user={}: {}", userId, e.getMessage());
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("user_id", userId);
                err.put("success", false);
                err.put("error", e.getMessage());
                results.add(err);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", userIds.size());
        out.put("success", success);
        out.put("results", results);
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> checkMissingRequiredAttrs(ObjectNode data, String categoryId) {
        MeliResponse resp = client.getPublic("/categories/" + categoryId + "/attributes");
        if (resp.status() != 200 || resp.data() == null || !resp.data().isArray()) return List.of();

        Set<String> existing = new HashSet<>();
        JsonNode attrs = data.path("attributes");
        if (attrs.isArray()) {
            for (JsonNode attr : attrs) {
                if (hasAttrValue(attr)) {
                    String id = attr.path("id").asText();
                    existing.add(id);
                    String alias = PACKAGE_ATTR_REMAP.getOrDefault(id, PACKAGE_ATTR_REMAP_INV.get(id));
                    if (alias != null) existing.add(alias);
                }
            }
        }

        Set<String> autoFillIds = Set.of("FAMILY_NAME", "BRAND", "PART_NUMBER", "MODEL", "GTIN");
        List<Map<String, Object>> missing = new ArrayList<>();
        for (JsonNode attr : resp.data()) {
            JsonNode tags = attr.path("tags");
            if (!tags.path("required").asBoolean(false) && !tags.path("catalog_required").asBoolean(false)) continue;
            String id = attr.path("id").asText("");
            if (id.isBlank() || existing.contains(id) || autoFillIds.contains(id)) continue;
            if (attr.hasNonNull("default_value")) continue;
            String valueType = attr.path("value_type").asText("string");
            if (attr.path("values").isArray() && !attr.path("values").isEmpty() && !"number_unit".equals(valueType)) continue;

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", attr.path("name").asText(id));
            m.put("value_type", valueType);
            m.put("default_unit", nullableText(attr, "default_unit"));
            m.put("allowed_units", units(attr.path("allowed_units")));
            m.put("values", values(attr.path("values"), 30));
            m.put("hint", attr.path("tooltip").asText(""));
            missing.add(m);
        }
        return missing;
    }

    private PreviewItem fetchPreviewItem(List<String> candidateIds) {
        List<Map<String, Object>> accounts = auth.listAccounts();
        for (String cid : candidateIds) {
            if (cid.startsWith("MLBU")) continue;
            for (Map<String, Object> account : accounts) {
                long uid = number(account.get("user_id")).longValue();
                try {
                    MeliResponse resp = client.get("/items/" + cid, uid);
                    if (resp.status() == 200 && resp.data() != null && resp.data().isObject()) {
                        return new PreviewItem((ObjectNode) resp.data().deepCopy(), cid, uid, false);
                    }
                } catch (Exception e) {
                    log.debug("clone auth fetch {} user={} falhou: {}", cid, uid, e.getMessage());
                }
            }
        }
        for (String cid : candidateIds) {
            if (cid.startsWith("MLBU")) continue;
            try {
                MeliResponse resp = client.getPublic("/items/" + cid);
                if (resp.status() == 200 && resp.data() != null && resp.data().isObject()) {
                    return new PreviewItem((ObjectNode) resp.data().deepCopy(), cid, null, false);
                }
            } catch (Exception e) {
                log.debug("clone public fetch {} falhou: {}", cid, e.getMessage());
            }
        }
        for (String cid : candidateIds) {
            if (!cid.startsWith("MLBU")) continue;
            PreviewItem item = fetchCatalogPreview(cid, accounts);
            if (item != null) return item;
        }
        return null;
    }

    private PreviewItem fetchCatalogPreview(String catalogId, List<Map<String, Object>> accounts) {
        List<Long> userIds = new ArrayList<>();
        for (Map<String, Object> account : accounts) {
            userIds.add(number(account.get("user_id")).longValue());
        }
        if (userIds.isEmpty()) return null;

        JsonNode offer = null;
        Long respondingUserId = null;
        for (Long userId : userIds) {
            try {
                MeliResponse resp = client.get("/products/" + catalogId + "/items",
                        Map.of("limit", "5"), userId);
                JsonNode results = resp.data() == null ? null : resp.data().path("results");
                if (resp.status() == 200 && results != null && results.isArray() && !results.isEmpty()) {
                    offer = results.get(0);
                    respondingUserId = userId;
                    break;
                }
                log.info("Clone catalogo {} via /products/{}/items user={} -> HTTP {} resultados={}",
                        catalogId, catalogId, userId, resp.status(),
                        results != null && results.isArray() ? results.size() : 0);
            } catch (Exception e) {
                log.debug("clone catalog offers {} user={} falhou: {}", catalogId, userId, e.getMessage());
            }
        }
        if (offer == null || !offer.isObject()) return null;

        String categoryId = offer.path("category_id").asText("");
        double price = offer.path("price").asDouble(0);
        String condition = offer.path("condition").asText("new");
        String currencyId = offer.path("currency_id").asText("BRL");
        String userProductId = offer.path("user_product_id").asText("");
        log.info("Clone catalogo {}: category_id={} price={} user_product_id={} via ofertas",
                catalogId, categoryId, price, userProductId);

        ObjectNode userProduct = fetchCatalogUserProduct(userProductId, userIds);
        ArrayNode attributes = userProduct == null
                ? MAPPER.createArrayNode()
                : attributesFromUserProduct(userProduct);
        ArrayNode pictures = userProduct == null
                ? MAPPER.createArrayNode()
                : picturesFromUserProduct(userProduct);
        String title = userProduct == null ? "" : userProduct.path("name").asText("");
        if (userProduct != null) {
            condition = conditionFromUserProduct(userProduct, condition);
            log.info("Clone catalogo {}: user-product trouxe {} attrs e {} fotos",
                    catalogId, attributes.size(), pictures.size());
        }

        ObjectNode item = MAPPER.createObjectNode();
        item.put("id", catalogId);
        item.put("title", title);
        item.put("price", price);
        item.put("category_id", categoryId);
        item.put("condition", condition);
        item.put("currency_id", currencyId);
        item.set("pictures", pictures);
        item.set("attributes", attributes);
        item.putNull("seller_id");
        item.put("permalink", "");
        item.put("available_quantity", 1);
        item.put("sold_quantity", 0);
        item.put("status", "");
        item.put("listing_type_id", "gold_special");
        item.set("shipping", MAPPER.createObjectNode());
        item.set("sale_terms", MAPPER.createArrayNode());
        item.put("_from_catalog", true);
        if (!userProductId.isBlank()) item.put("user_product_id", userProductId);
        return new PreviewItem(item, catalogId, respondingUserId, true);
    }

    private ObjectNode fetchCatalogUserProduct(String userProductId, List<Long> userIds) {
        if (userProductId == null || userProductId.isBlank()) return null;
        for (Long userId : userIds) {
            try {
                MeliResponse resp = client.get("/user-products/" + userProductId, userId);
                if (resp.status() == 200 && resp.data() != null && resp.data().isObject()) {
                    return (ObjectNode) resp.data().deepCopy();
                }
                log.debug("Clone catalogo user-product {} user={} -> HTTP {}",
                        userProductId, userId, resp.status());
            } catch (Exception e) {
                log.debug("Clone catalogo user-product {} user={} falhou: {}",
                        userProductId, userId, e.getMessage());
            }
        }
        return null;
    }

    private static ArrayNode attributesFromUserProduct(ObjectNode userProduct) {
        ArrayNode out = MAPPER.createArrayNode();
        JsonNode attrs = userProduct.path("attributes");
        if (!attrs.isArray()) return out;

        for (JsonNode attr : attrs) {
            String id = attr.path("id").asText("");
            if (id.isBlank() || SKIP_ATTR_IDS.contains(id)) continue;

            List<JsonNode> cleanValues = new ArrayList<>();
            if (attr.path("values").isArray()) {
                for (JsonNode value : attr.path("values")) {
                    String valueId = value.path("id").asText(null);
                    String valueName = value.path("name").asText(null);
                    if ((valueId != null && !"-1".equals(valueId))
                            || (valueName != null && !valueName.isBlank())) {
                        cleanValues.add(value);
                    }
                }
            }
            if (cleanValues.isEmpty()) continue;

            JsonNode first = cleanValues.get(0);
            ObjectNode converted = MAPPER.createObjectNode();
            converted.put("id", id);
            if (attr.hasNonNull("name")) converted.put("name", attr.path("name").asText());
            String valueId = first.path("id").asText(null);
            String valueName = first.path("name").asText(null);
            if (valueId != null && !"-1".equals(valueId)) converted.put("value_id", valueId);
            if (valueName != null) converted.put("value_name", valueName);
            if (first.hasNonNull("struct")) converted.set("value_struct", first.get("struct").deepCopy());
            if (cleanValues.size() > 1) {
                ArrayNode values = converted.putArray("values");
                for (JsonNode value : cleanValues) values.add(value.deepCopy());
            }
            out.add(converted);
        }
        return out;
    }

    private static ArrayNode picturesFromUserProduct(ObjectNode userProduct) {
        ArrayNode out = MAPPER.createArrayNode();
        JsonNode pictures = userProduct.path("pictures");
        if (!pictures.isArray()) return out;
        for (JsonNode picture : pictures) {
            String url = picture.path("secure_url").asText(picture.path("url").asText(""));
            if (url.isBlank()) continue;
            ObjectNode converted = MAPPER.createObjectNode();
            converted.put("url", url);
            converted.put("secure_url", picture.path("secure_url").asText(url));
            if (picture.hasNonNull("id")) converted.put("id", picture.path("id").asText());
            out.add(converted);
        }
        return out;
    }

    private static String conditionFromUserProduct(ObjectNode userProduct, String fallback) {
        for (JsonNode attr : userProduct.path("attributes")) {
            if (!"ITEM_CONDITION".equals(attr.path("id").asText())) continue;
            JsonNode values = attr.path("values");
            if (values.isArray() && !values.isEmpty()) {
                return "2230581".equals(values.get(0).path("id").asText()) ? "used" : "new";
            }
        }
        return fallback;
    }

    private String fetchDescription(String itemId, Long userId) {
        if (!itemId.startsWith("MLB")) return "";
        try {
            MeliResponse resp = userId == null
                    ? client.getPublic("/items/" + itemId + "/description")
                    : client.get("/items/" + itemId + "/description", userId);
            if (resp.status() == 200 && resp.data() != null) {
                String plain = resp.data().path("plain_text").asText("");
                if (!plain.isBlank()) return plain;
                return resp.data().path("text").asText("");
            }
        } catch (Exception e) {
            log.warn("clone description {} falhou: {}", itemId, e.getMessage());
        }
        return "";
    }

    private ArrayNode fetchVariations(String itemId, Long userId) {
        try {
            MeliResponse resp = userId == null
                    ? client.getPublic("/items/" + itemId + "/variations")
                    : client.get("/items/" + itemId + "/variations", userId);
            if (resp.status() == 200 && resp.data() != null && resp.data().isArray()) {
                return (ArrayNode) resp.data();
            }
        } catch (Exception ignored) {
        }
        return MAPPER.createArrayNode();
    }

    private List<Map<String, Object>> fetchCompatibilities(String originalRef, String itemId, Long ownerUserId) {
        try {
            if (ownerUserId != null && itemId.startsWith("MLB")) {
                return bulk.getItemCompatibilities(itemId, ownerUserId);
            }
            Object compats = bulk.getCompatibilitiesFromRef(originalRef).get("compatibilities");
            if (compats instanceof List<?> list) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Object it : list) if (it instanceof Map<?, ?> m) out.add(stringObjectMap(m));
                return out;
            }
        } catch (Exception e) {
            log.warn("clone compat {} falhou: {}", itemId, e.getMessage());
        }
        return List.of();
    }

    private List<Map<String, Object>> fetchPositionRestrictions(String itemId, Long ownerUserId, String title) {
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> out = new ArrayList<>();
        if (ownerUserId != null) {
            try {
                Object positionsObj = bulk.getItemCompatibilitiesWithPositions(itemId, ownerUserId).get("positions");
                if (positionsObj instanceof List<?> positions) {
                    for (Object p : positions) {
                        if (!(p instanceof Map<?, ?> raw)) continue;
                        PositionValue v = resolvePositionValue(stringObjectMap(raw));
                        if (v != null && seen.add(v.id())) {
                            out.add(Map.of("value_id", v.id(), "value_name", v.name()));
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("clone positions {} falhou: {}", itemId, e.getMessage());
            }
        }
        if (out.isEmpty()) {
            for (PositionValue v : inferPositionsFromTitle(title)) {
                if (seen.add(v.id())) out.add(Map.of("value_id", v.id(), "value_name", v.name()));
            }
        }
        return out;
    }

    private Set<String> getBlockedAttributeIds(String categoryId) {
        Set<String> blocked = new HashSet<>();
        if (categoryId == null || categoryId.isBlank()) return blocked;
        try {
            MeliResponse resp = client.getPublic("/categories/" + categoryId + "/attributes");
            if (resp.status() != 200 || resp.data() == null || !resp.data().isArray()) return blocked;
            for (JsonNode attr : resp.data()) {
                JsonNode tags = attr.path("tags");
                if (tags.path("read_only").asBoolean(false)
                        || tags.path("hidden").asBoolean(false)
                        || tags.path("inferred").asBoolean(false)) {
                    blocked.add(attr.path("id").asText());
                }
            }
        } catch (Exception e) {
            log.warn("attrs categoria {} falhou: {}", categoryId, e.getMessage());
        }
        return blocked;
    }

    private List<Map<String, Object>> filterAttributes(List<JsonNode> attrs, Set<String> blockedIds) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode attr : attrs) {
            String id = attr.path("id").asText("");
            if (id.isBlank() || SKIP_ATTR_IDS.contains(id) || blockedIds.contains(id) || !hasAttrValue(attr)) continue;
            Map<String, Object> clean = new LinkedHashMap<>();
            clean.put("id", id);
            putIfText(clean, "name", attr, "name");
            putIfText(clean, "value_id", attr, "value_id");
            putIfText(clean, "value_name", attr, "value_name");
            if (attr.hasNonNull("value_struct")) clean.put("value_struct", jsonToObject(attr.get("value_struct")));
            out.add(clean);
        }
        return out;
    }

    /**
     * Para anúncio de catálogo de terceiro, o ML costuma liberar categoria e
     * preço em /products/{MLBU}/items, mas bloquear atributos e fotos. Nesse
     * caso devolvemos os campos editáveis da categoria sem valor para o usuário
     * completar o preview, em vez de falhar com 400.
     */
    private List<Map<String, Object>> categoryAttributeForm(String categoryId) {
        if (categoryId == null || categoryId.isBlank()) return List.of();
        try {
            MeliResponse resp = client.getPublic("/categories/" + categoryId + "/attributes");
            if (resp.status() != 200 || resp.data() == null || !resp.data().isArray()) return List.of();

            List<Map<String, Object>> out = new ArrayList<>();
            for (JsonNode attr : resp.data()) {
                String id = attr.path("id").asText("");
                JsonNode tags = attr.path("tags");
                if (id.isBlank() || FORM_SKIP_ATTR_IDS.contains(id)
                        || canonicalPackageAttrId(id) != null
                        || tags.path("read_only").asBoolean(false)
                        || tags.path("hidden").asBoolean(false)
                        || tags.path("inferred").asBoolean(false)) {
                    continue;
                }
                Map<String, Object> field = new LinkedHashMap<>();
                field.put("id", id);
                field.put("name", attr.path("name").asText(id));
                field.put("value_name", "");
                out.add(field);
            }
            log.info("Clone catalogo: formulario da categoria {} com {} campos", categoryId, out.size());
            return out;
        } catch (Exception e) {
            log.warn("Clone catalogo: falha montando formulario da categoria {}: {}",
                    categoryId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Injeta medidas de embalagem na ordem do Python: attrs do item →
     * user-product → shipping.dimensions → default por categoria.
     * Retorna o mapa de defaults quando eles foram usados (senão null),
     * pra virar {@code _used_default_dims} no preview.
     */
    private Map<String, Number> injectPackageAttrs(List<JsonNode> itemAttrs, ObjectNode item,
                                                   String categoryId, Long ownerUserId) {
        Set<String> existingIds = new HashSet<>();
        for (JsonNode attr : itemAttrs) {
            String id = attr.path("id").asText("");
            existingIds.add(canonicalPackageAttrId(id) == null ? id : canonicalPackageAttrId(id));
        }
        for (JsonNode attr : fetchPackageAttrsFromUp(item, ownerUserId)) {
            String id = attr.path("id").asText("");
            String canonical = canonicalPackageAttrId(id);
            if (existingIds.add(canonical == null ? id : canonical)) itemAttrs.add(attr);
        }
        for (JsonNode attr : parseShippingDimensions(item.path("shipping").path("dimensions").asText(null))) {
            String id = attr.path("id").asText("");
            String canonical = canonicalPackageAttrId(id);
            if (existingIds.add(canonical == null ? id : canonical)) itemAttrs.add(attr);
        }
        boolean hasPackage = itemAttrs.stream().anyMatch(a -> a.path("id").asText("").toUpperCase(Locale.ROOT).contains("PACKAGE"));
        if (hasPackage) return null;

        Map<String, Number> dims = DEFAULT_DIMS_BY_CATEGORY.getOrDefault(categoryId, DEFAULT_DIMS_FALLBACK);
        addPackageAttr(itemAttrs, "SELLER_PACKAGE_HEIGHT", dims.get("height"), "cm");
        addPackageAttr(itemAttrs, "SELLER_PACKAGE_WIDTH", dims.get("width"), "cm");
        addPackageAttr(itemAttrs, "SELLER_PACKAGE_LENGTH", dims.get("length"), "cm");
        addPackageAttr(itemAttrs, "SELLER_PACKAGE_WEIGHT", dims.get("weight"), "g");
        log.warn("Clone preview — sem medida no source ({}, cat={}). Aplicando default {}",
                item.path("id").asText(""), categoryId, dims);
        return dims;
    }

    /** Itens vinculados a user-product guardam as medidas no UP, nao no item. */
    private List<JsonNode> fetchPackageAttrsFromUp(ObjectNode item, Long ownerUserId) {
        if (item.path("_from_catalog").asBoolean(false)) return List.of();
        String upId = item.path("user_product_id").asText(null);
        if (upId == null || upId.isBlank()) return List.of();
        try {
            MeliResponse resp = client.get("/user-products/" + upId, ownerUserId);
            if (resp.status() != 200 || resp.data() == null) return List.of();
            List<JsonNode> out = new ArrayList<>();
            for (JsonNode attr : resp.data().path("attributes")) {
                if (PACKAGE_ANY_IDS.contains(attr.path("id").asText(""))) out.add(attr.deepCopy());
            }
            return out;
        } catch (Exception e) {
            log.warn("Falha lendo medidas do user-product de {}: {}",
                    item.path("id").asText(""), e.getMessage());
            return List.of();
        }
    }

    /**
     * Medidas nunca podem ser filtradas pelo allowlist da categoria — o ML exige
     * as dimensoes e o remap do create() converte pra seller_package_*.
     */
    private static void forcePackageAttrs(List<Map<String, Object>> copyableAttrs, List<JsonNode> itemAttrs) {
        List<Map<String, Object>> regularAttrs = new ArrayList<>();
        Map<String, Map<String, Object>> packageAttrs = new LinkedHashMap<>();
        for (Map<String, Object> attr : copyableAttrs) {
            String canonicalId = canonicalPackageAttrId(String.valueOf(attr.get("id")));
            if (canonicalId == null) {
                regularAttrs.add(attr);
                continue;
            }
            packageAttrs.putIfAbsent(canonicalId, canonicalPackageAttr(attr, canonicalId));
        }

        for (JsonNode attr : itemAttrs) {
            String id = attr.path("id").asText("");
            String canonicalId = canonicalPackageAttrId(id);
            if (canonicalId == null || packageAttrs.containsKey(canonicalId)) continue;
            Map<String, Object> clean = new LinkedHashMap<>();
            clean.put("id", canonicalId);
            clean.put("name", packageAttrName(canonicalId));
            clean.put("value_name", attr.path("value_name").asText(null));
            clean.put("value_struct", attr.hasNonNull("value_struct") ? jsonToObject(attr.get("value_struct")) : null);
            packageAttrs.put(canonicalId, clean);
        }

        copyableAttrs.clear();
        copyableAttrs.addAll(regularAttrs);
        copyableAttrs.addAll(packageAttrs.values());
    }

    private static Map<String, Object> canonicalPackageAttr(Map<String, Object> source, String canonicalId) {
        Map<String, Object> normalized = new LinkedHashMap<>(source);
        normalized.put("id", canonicalId);
        normalized.put("name", packageAttrName(canonicalId));
        return normalized;
    }

    private static String canonicalPackageAttrId(String id) {
        if (id == null) return null;
        return switch (id.toUpperCase(Locale.ROOT)) {
            case "PACKAGE_HEIGHT", "SELLER_PACKAGE_HEIGHT" -> "seller_package_height";
            case "PACKAGE_WIDTH", "SELLER_PACKAGE_WIDTH" -> "seller_package_width";
            case "PACKAGE_LENGTH", "SELLER_PACKAGE_LENGTH" -> "seller_package_length";
            case "PACKAGE_WEIGHT", "SELLER_PACKAGE_WEIGHT" -> "seller_package_weight";
            default -> null;
        };
    }

    private static String packageAttrName(String canonicalId) {
        return switch (canonicalId) {
            case "seller_package_height" -> "Altura da embalagem";
            case "seller_package_width" -> "Largura da embalagem";
            case "seller_package_length" -> "Comprimento da embalagem";
            case "seller_package_weight" -> "Peso da embalagem";
            default -> canonicalId;
        };
    }

    private List<JsonNode> parseShippingDimensions(String raw) {
        if (raw == null || raw.isBlank() || !raw.contains(",")) return List.of();
        try {
            String[] parts = raw.split(",", 2);
            String[] dims = parts[0].toLowerCase(Locale.ROOT).split("x");
            if (dims.length != 3) return List.of();
            List<JsonNode> out = new ArrayList<>();
            out.add(packageAttr("PACKAGE_LENGTH", Double.parseDouble(dims[0].strip()), "cm"));
            out.add(packageAttr("PACKAGE_WIDTH", Double.parseDouble(dims[1].strip()), "cm"));
            out.add(packageAttr("PACKAGE_HEIGHT", Double.parseDouble(dims[2].strip()), "cm"));
            out.add(packageAttr("PACKAGE_WEIGHT", Double.parseDouble(parts[1].strip()), "g"));
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private void normalizeAttributesForCreate(ObjectNode data) {
        JsonNode attrsNode = data.path("attributes");
        if (!attrsNode.isArray()) return;
        ArrayNode out = MAPPER.createArrayNode();
        Set<String> seen = new HashSet<>();
        for (JsonNode raw : attrsNode) {
            if (!raw.isObject()) continue;
            ObjectNode attr = (ObjectNode) raw.deepCopy();
            String id = attr.path("id").asText("");
            String remapped = PACKAGE_ATTR_REMAP.get(id);
            if (remapped != null) {
                id = remapped;
                attr.put("id", id);
            }
            if (!seen.add(id)) continue;
            if (PACKAGE_LOWER_IDS.contains(id)) roundPackageAttr(attr, id);
            out.add(attr);
        }
        data.set("attributes", out);
    }

    private void ensureRequiredAttributes(ObjectNode data, String categoryId) {
        MeliResponse resp = client.getPublic("/categories/" + categoryId + "/attributes");
        if (resp.status() != 200 || resp.data() == null || !resp.data().isArray()) return;
        Set<String> existing = new HashSet<>();
        for (JsonNode attr : data.path("attributes")) existing.add(attr.path("id").asText(""));
        ArrayNode attrs = data.withArray("attributes");
        String title = data.path("title").asText(data.path("family_name").asText(""));

        for (JsonNode attr : resp.data()) {
            JsonNode tags = attr.path("tags");
            if (!tags.path("required").asBoolean(false) && !tags.path("catalog_required").asBoolean(false)) continue;
            String id = attr.path("id").asText("");
            if (id.isBlank() || existing.contains(id)) continue;

            ObjectNode newAttr = MAPPER.createObjectNode();
            newAttr.put("id", id);
            if (attr.hasNonNull("default_value")) {
                JsonNode def = attr.path("default_value");
                if (def.isObject()) {
                    putText(newAttr, "value_id", def, "id");
                    putText(newAttr, "value_name", def, "name");
                } else {
                    // ML devolve default_value escalar; e o proprio value_id (como no Python).
                    newAttr.put("value_id", def.asText());
                }
            } else if ("FAMILY_NAME".equals(id)) {
                newAttr.put("value_name", title);
            } else if ("BRAND".equals(id)) {
                newAttr.put("value_name", title.isBlank() ? "Generico" : title.split("\\s+")[0]);
            } else if ("PART_NUMBER".equals(id)) {
                newAttr.put("value_name", "N/A");
            } else if ("MODEL".equals(id)) {
                newAttr.put("value_name", title.length() > 60 ? title.substring(0, 60) : (title.isBlank() ? "N/A" : title));
            } else if ("GTIN".equals(id)) {
                continue;
            } else if (attr.path("values").isArray() && !attr.path("values").isEmpty()
                    && !"number_unit".equals(attr.path("value_type").asText())) {
                JsonNode v = attr.path("values").get(0);
                putText(newAttr, "value_id", v, "id");
                putText(newAttr, "value_name", v, "name");
            } else {
                continue;
            }
            attrs.add(newAttr);
            existing.add(id);
        }
    }

    private int applyCompatibilities(ObjectNode newItem, String newItemId, ArrayNode compatibilities,
                                     ArrayNode positionRestrictions, String sourceItemId, Long userId) {
        if (compatibilities == null || compatibilities.isEmpty()) return 0;
        String domainId = compatibilities.get(0).path("domain_id").asText(CARS_DOMAIN_ID);
        String upId = newItem.path("user_product_id").asText(null);
        if (upId == null || upId.isBlank()) {
            try {
                MeliResponse reread = client.get("/items/" + newItemId, userId);
                upId = reread.data() == null ? null : reread.data().path("user_product_id").asText(null);
            } catch (Exception ignored) {
            }
        }
        String compatPath = upId == null || upId.isBlank()
                ? "/items/" + newItemId + "/compatibilities"
                : "/user-products/" + upId + "/compatibilities";

        int applied = 0;
        if (sourceItemId != null && !sourceItemId.isBlank()) {
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("domain_id", domainId);
            ObjectNode create = payload.putObject("create");
            ObjectNode itemToCopy = create.putObject("item_to_copy");
            itemToCopy.put("item_id", sourceItemId);
            itemToCopy.put("extended_information", true);
            MeliResponse resp = client.put(compatPath, userId, payload);
            if (resp.status() == 200 || resp.status() == 201) {
                // ML processa item_to_copy assincronamente: 200 e so um ack.
                // Sem o read-back, o clone podia terminar "ok" com compat vazia.
                sleepQuietly(800);
                try {
                    MeliResponse verify = client.get(compatPath, userId);
                    JsonNode products = verify.data() == null ? null : verify.data().path("products");
                    if (verify.status() == 200 && products != null && products.isArray() && !products.isEmpty()) {
                        applied = products.size();
                    } else {
                        log.warn("item_to_copy {}: ML ackou (200) mas compat vazia no read-back — caindo pro manual",
                                newItemId);
                    }
                } catch (Exception e) {
                    log.warn("Verificacao do item_to_copy {} falhou: {}", newItemId, e.getMessage());
                }
            } else {
                log.warn("Clone compat (item_to_copy) {}: status={} {}", newItemId, resp.status(), resp.data());
            }
        }
        if (applied == 0) {
            List<Map<String, Object>> products = new ArrayList<>();
            List<Map<String, Object>> restrictions = positionRestrictions(positionRestrictions);
            for (JsonNode c : compatibilities) {
                String pid = c.path("catalog_product_id").asText(c.path("product_id").asText(""));
                if (pid.isBlank()) continue;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", pid);
                String note = c.path("note").asText("");
                if (!note.isBlank()) entry.put("note", note);
                if (!restrictions.isEmpty()) entry.put("restrictions", restrictions);
                products.add(entry);
            }
            for (int i = 0; i < products.size(); i += COMPAT_BATCH) {
                List<Map<String, Object>> chunk = products.subList(i, Math.min(i + COMPAT_BATCH, products.size()));
                Map<String, Object> payload = Map.of("domain_id", domainId, "create", Map.of("products", chunk));
                MeliResponse resp = client.put(compatPath, userId, payload);
                // Mesmo padrao do Python: 200 com create.products vazio = ML ignorou o chunk.
                JsonNode created = resp.data() == null ? null : resp.data().path("create").path("products");
                if ((resp.status() == 200 || resp.status() == 201)
                        && created != null && created.isArray() && !created.isEmpty()) {
                    applied += chunk.size();
                } else {
                    log.warn("Clone compat fallback {} falhou (chunk {}): status={} {}",
                            newItemId, i / COMPAT_BATCH + 1, resp.status(), resp.data());
                }
            }
        }
        if (upId != null && !upId.isBlank() && positionRestrictions != null && !positionRestrictions.isEmpty()) {
            applyPositionUpdate(upId, domainId, positionRestrictions, userId);
        }
        return applied;
    }

    private void applyPositionUpdate(String upId, String domainId, ArrayNode positionRestrictions, Long userId) {
        List<Map<String, Object>> restrictions = positionRestrictions(positionRestrictions);
        if (restrictions.isEmpty()) return;
        // ML persiste a compat de forma assincrona; sem a pausa o GET pode vir vazio.
        sleepQuietly(500);
        MeliResponse list = client.get("/user-products/" + upId + "/compatibilities",
                Map.of("main_domain_id", domainId), userId);
        JsonNode products = list.data() == null ? null : list.data().path("products");
        if (products == null || !products.isArray() || products.isEmpty()) return;
        List<Map<String, Object>> updateProducts = new ArrayList<>();
        for (JsonNode p : products) {
            String pid = p.path("catalog_product_id").asText(p.path("product_id").asText(""));
            if (pid.isBlank()) continue;
            updateProducts.add(Map.of("id", pid, "restrictions", restrictions));
        }
        if (updateProducts.isEmpty()) return;
        Map<String, Object> payload = Map.of("domain_id", domainId, "update", Map.of("products", updateProducts));
        MeliResponse resp = client.put("/user-products/" + upId + "/compatibilities", userId, payload);
        if (resp.status() != 200 && resp.status() != 201) {
            log.warn("clone position update up={} status={} {}", upId, resp.status(), resp.data());
        }
    }

    private static List<Map<String, Object>> positionRestrictions(ArrayNode positionRestrictions) {
        if (positionRestrictions == null || positionRestrictions.isEmpty()) return List.of();
        List<Map<String, Object>> values = new ArrayList<>();
        for (JsonNode p : positionRestrictions) {
            String id = p.path("value_id").asText("");
            if (id.isBlank()) continue;
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("value_id", id);
            v.put("value_name", p.path("value_name").asText(""));
            values.add(v);
        }
        if (values.isEmpty()) return List.of();
        return List.of(Map.of("attribute_id", "POSITION",
                "attribute_values", List.of(Map.of("values", values))));
    }

    private void applySku(String itemId, String sku, Long userId) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("seller_custom_field", sku);
        ArrayNode attrs = body.putArray("attributes");
        ObjectNode attr = attrs.addObject();
        attr.put("id", "SELLER_SKU");
        attr.put("value_name", sku);
        MeliResponse resp = client.put("/items/" + itemId, userId, body);
        if (resp.status() != 200 && resp.status() != 201) {
            log.warn("Falha ao definir SKU {} em {}: {}", sku, itemId, resp.data());
        }
    }

    private void applyDescription(String itemId, String description, Long userId) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("plain_text", description);
        MeliResponse resp = client.post("/items/" + itemId + "/description", userId, body);
        if (resp.status() != 200 && resp.status() != 201) {
            log.warn("Falha ao aplicar descricao em {}: {}", itemId, resp.data());
        }
    }

    private void saveCloneLog(Long userId, String batchId, String itemId, String title, String listingType,
                              String sourceItemId, String sku, JsonNode request, JsonNode response,
                              String status, String errorMessage) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("title", title);
        payload.put("seller_custom_field", sku);
        payload.put("listing_type_id", listingType);
        payload.put("source_item_id", sourceItemId);
        payload.set("request", request == null ? MAPPER.nullNode() : request);
        OperationLog entry = new OperationLog("clone",
                itemId == null ? List.of() : List.of(itemId),
                payload,
                response == null ? MAPPER.nullNode() : response,
                status,
                errorMessage);
        entry.setUserId(userId);
        entry.setBatchId(batchId);
        logs.save(entry);
    }

    private static List<String> extractItemIds(String input) {
        String cleaned = input == null ? "" : input.toUpperCase(Locale.ROOT).replace("-", "");
        List<String> ids = new ArrayList<>();
        Matcher query = ITEM_ID_QUERY.matcher(cleaned);
        if (query.find()) ids.add(query.group(1));
        Matcher item = ITEM_ID_PATTERN.matcher(cleaned);
        while (item.find()) if (!ids.contains(item.group(1))) ids.add(item.group(1));
        Matcher mlbu = MLBU_PATTERN.matcher(cleaned);
        while (mlbu.find()) if (!ids.contains(mlbu.group(1))) ids.add(mlbu.group(1));
        if (ids.isEmpty() && input != null && !input.isBlank()) ids.add(input.strip());
        return ids;
    }

    private static List<PositionValue> inferPositionsFromTitle(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        List<PositionValue> out = new ArrayList<>();
        if (Pattern.compile("\\bdianteir[oa]s?\\b|\\bdiant\\.?\\b|\\bfrontal\\b|\\bfront\\b").matcher(t).find()) {
            out.add(new PositionValue("13701104", "Dianteira"));
        }
        if (Pattern.compile("\\btraseir[oa]s?\\b|\\btras\\.?\\b|\\brear\\b").matcher(t).find()) {
            out.add(new PositionValue("13701105", "Traseira"));
        }
        if (Pattern.compile("\\besquerd[oa]s?\\b|\\besq\\.?\\b|\\bl\\.?h\\.?\\b|\\bleft\\b").matcher(t).find()) {
            out.add(new PositionValue("2262158", "Esquerda"));
        }
        if (Pattern.compile("\\bdireit[oa]s?\\b|\\bdir\\.?\\b|\\br\\.?h\\.?\\b|\\bright\\b").matcher(t).find()) {
            out.add(new PositionValue("2262160", "Direita"));
        }
        return out;
    }

    private static PositionValue resolvePositionValue(Map<String, Object> p) {
        String name = p.get("value_name") == null ? "" : String.valueOf(p.get("value_name"));
        String key = name.toLowerCase(Locale.ROOT).strip();
        String stem = key.replaceAll("[aeiou]$", "");
        if (stem.endsWith("direit")) return new PositionValue("2262160", "Direita");
        if (stem.endsWith("esquerd")) return new PositionValue("2262158", "Esquerda");
        if (stem.endsWith("dianteir")) return new PositionValue("13701104", "Dianteira");
        if (stem.endsWith("traseir")) return new PositionValue("13701105", "Traseira");
        return null;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean hasAttrValue(JsonNode attr) {
        return attr.hasNonNull("value_id")
                || attr.hasNonNull("value_name")
                || attr.hasNonNull("value_struct")
                || (attr.path("values").isArray() && !attr.path("values").isEmpty());
    }

    private static void roundPackageAttr(ObjectNode attr, String id) {
        JsonNode struct = attr.path("value_struct");
        Double num = struct.hasNonNull("number")
                ? Double.valueOf(struct.path("number").asDouble())
                : parseLeadingNumber(attr.path("value_name").asText(""));
        if (num == null) return;
        String unit = struct.path("unit").asText(id.contains("weight") ? "g" : "cm");
        int rounded = (int) Math.round(num);
        attr.put("value_name", rounded + " " + unit);
        ObjectNode newStruct = MAPPER.createObjectNode();
        newStruct.put("number", rounded);
        newStruct.put("unit", unit);
        attr.set("value_struct", newStruct);
    }

    private static Double parseLeadingNumber(String text) {
        Matcher m = Pattern.compile("^\\s*([\\d.,]+)").matcher(text == null ? "" : text);
        if (!m.find()) return null;
        try {
            return Double.parseDouble(m.group(1).replace(",", "."));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void addPackageAttr(List<JsonNode> attrs, String id, Number number, String unit) {
        attrs.add(packageAttr(id, number.doubleValue(), unit));
    }

    private static JsonNode packageAttr(String id, double number, String unit) {
        ObjectNode attr = MAPPER.createObjectNode();
        attr.put("id", id);
        attr.put("value_name", number + " " + unit);
        ObjectNode struct = attr.putObject("value_struct");
        struct.put("number", number);
        struct.put("unit", unit);
        return attr;
    }

    private static List<JsonNode> copyArray(JsonNode node) {
        List<JsonNode> out = new ArrayList<>();
        if (node != null && node.isArray()) node.forEach(n -> out.add(n.deepCopy()));
        return out;
    }

    private static List<Map<String, Object>> pictureSources(JsonNode pictures) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (pictures != null && pictures.isArray()) {
            for (JsonNode p : pictures) {
                String source = p.path("secure_url").asText(p.path("url").asText(""));
                if (!source.isBlank()) out.add(Map.of("source", source));
            }
        }
        return out;
    }

    private static String textAndRemove(ObjectNode node, String field) {
        JsonNode value = node.remove(field);
        return value == null || value.isNull() ? null : value.asText(null);
    }

    private static ArrayNode arrayAndRemove(ObjectNode node, String field) {
        JsonNode value = node.remove(field);
        return value != null && value.isArray() ? (ArrayNode) value : MAPPER.createArrayNode();
    }

    private static boolean mentionsFamilyName(JsonNode data) {
        return data != null && data.toString().toLowerCase(Locale.ROOT).contains("family_name");
    }

    private static String readableCreateError(JsonNode data) {
        if (data == null) return "Erro ao criar item";
        JsonNode cause = data.path("cause");
        if (cause.isArray()) {
            for (JsonNode c : cause) {
                String code = c.path("code").asText("").toLowerCase(Locale.ROOT);
                String msg = c.path("message").asText("");
                if (code.contains("seller_package") || code.contains("package.dimensions")) {
                    return "Medidas da embalagem obrigatorias ou invalidas. Detalhe ML: " + msg;
                }
            }
        }
        String msg = data.path("message").asText(data.path("error").asText(""));
        return msg.isBlank() ? "Erro ao criar item: " + data : "Erro ao criar item: " + msg;
    }

    private static Object jsonToObject(JsonNode node) {
        if (node == null || node.isMissingNode()) return null;
        return MAPPER.convertValue(node, Object.class);
    }

    private static void putIfText(Map<String, Object> map, String outKey, JsonNode node, String inKey) {
        String value = node.path(inKey).asText(null);
        if (value != null && !value.isBlank()) map.put(outKey, value);
    }

    private static void putText(ObjectNode out, String outKey, JsonNode node, String inKey) {
        String value = node.path(inKey).asText(null);
        if (value != null && !value.isBlank()) out.put(outKey, value);
    }

    private static String nullableText(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static List<Map<String, Object>> units(JsonNode units) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (units != null && units.isArray()) {
            for (JsonNode u : units) {
                if (u.hasNonNull("id")) out.add(Map.of("id", u.path("id").asText(), "name", u.path("name").asText("")));
            }
        }
        return out;
    }

    private static List<Map<String, Object>> values(JsonNode values, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (values != null && values.isArray()) {
            for (JsonNode v : values) {
                if (out.size() >= limit) break;
                if (v.hasNonNull("id")) out.add(Map.of("id", v.path("id").asText(), "name", v.path("name").asText("")));
            }
        }
        return out;
    }

    private static String firstText(JsonNode node, String k1, String k2, String fallback) {
        String v1 = node.path(k1).asText(null);
        if (v1 != null && !v1.isBlank()) return v1;
        String v2 = node.path(k2).asText(null);
        if (v2 != null && !v2.isBlank()) return v2;
        return fallback;
    }

    private static Number number(Object value) {
        if (value instanceof Number n) return n;
        return Long.parseLong(String.valueOf(value));
    }

    private static Map<String, Object> stringObjectMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            if (e.getKey() != null) out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }
}
