package com.hrb.mlmanager.meli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hrb.mlmanager.meli.MeliClient.MeliResponse;
import com.hrb.mlmanager.ops.OperationLog;
import com.hrb.mlmanager.ops.OperationLogRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operações sobre anúncios do vendedor. Espelho de backend/app/services/meli_items.py.
 * Como no Python, todas as chamadas ao ML usam o token da conta ativa
 * (userId null no client); o {@code userId} aqui serve só para montar os paths
 * de busca por vendedor.
 */
@Service
public class MeliItemsService {

    private static final Logger log = LoggerFactory.getLogger(MeliItemsService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> NON_PENDING = Set.of("active", "paused", "closed");

    static final List<String> ITEM_FIELDS = List.of(
            "id", "title", "price", "available_quantity", "sold_quantity",
            "status", "sub_status", "pictures", "thumbnail",
            "permalink", "category_id", "seller_custom_field",
            "attributes", "shipping", "listing_type_id",
            "date_created", "last_updated", "variations");

    private final MeliClient client;
    private final OperationLogRepository logs;

    public MeliItemsService(MeliClient client, OperationLogRepository logs) {
        this.client = client;
        this.logs = logs;
    }

    public record UpdateResult(int status, JsonNode data, List<String> ignoredAttrs) {}

    // ---- Busca ---------------------------------------------------------------

    /** Status especial "pending" = tudo que NÃO é active/paused/closed (varredura client-side). */
    public Map<String, Object> searchItems(long userId, String status, String sellerSku,
                                           String q, int offset, int limit) {
        if ("pending".equals(status)) {
            return searchPending(userId, q, offset, limit, 1000);
        }

        Map<String, String> params = new LinkedHashMap<>();
        params.put("offset", String.valueOf(offset));
        params.put("limit", String.valueOf(Math.min(limit, 50)));
        if (status != null) params.put("status", status);
        if (sellerSku != null) params.put("seller_sku", sellerSku);

        MeliResponse resp = client.get("/users/" + userId + "/items/search", params, null);
        JsonNode data = resp.data();
        List<String> itemIds = textList(data == null ? null : data.path("results"));

        Map<String, Object> out = new LinkedHashMap<>();
        if (itemIds.isEmpty()) {
            out.put("items", List.of());
            out.put("paging", paging(data));
            return out;
        }

        List<JsonNode> items = client.multiGetItems(itemIds, ITEM_FIELDS, null);
        if (q != null) items = filterByTitle(items, q);

        out.put("items", items);
        out.put("paging", paging(data));
        return out;
    }

    private Map<String, Object> searchPending(long userId, String q, int offset, int limit, int maxScan) {
        List<JsonNode> pending = new ArrayList<>();
        int scanOffset = 0;
        Integer mlTotal = null;

        while (scanOffset < maxScan) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("offset", String.valueOf(scanOffset));
            params.put("limit", "50");
            MeliResponse resp = client.get("/users/" + userId + "/items/search", params, null);
            JsonNode data = resp.data();
            if (mlTotal == null) mlTotal = data == null ? 0 : data.path("paging").path("total").asInt(0);

            List<String> batchIds = textList(data == null ? null : data.path("results"));
            if (batchIds.isEmpty()) break;

            for (JsonNode item : client.multiGetItems(batchIds, ITEM_FIELDS, null)) {
                if (!NON_PENDING.contains(item.path("status").asText(""))) pending.add(item);
            }

            scanOffset += batchIds.size();
            if (mlTotal != null && scanOffset >= mlTotal) break;
        }

        if (q != null) pending = filterByTitle(pending, q);
        log.info("Pendentes user={}: {} encontrados (varridos {}/{})",
                userId, pending.size(), scanOffset, mlTotal);

        List<JsonNode> page = pending.subList(Math.min(offset, pending.size()),
                Math.min(offset + limit, pending.size()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", new ArrayList<>(page));
        Map<String, Object> pg = new LinkedHashMap<>();
        pg.put("total", pending.size());
        pg.put("offset", offset);
        pg.put("limit", limit);
        out.put("paging", pg);
        return out;
    }

    /** Conta itens não-active/paused/closed (usado pelo dashboard). Espelho de count_pending. */
    public int countPending(long userId, int maxScan) {
        int count = 0;
        int scanOffset = 0;
        Integer mlTotal = null;

        while (scanOffset < maxScan) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("offset", String.valueOf(scanOffset));
            params.put("limit", "50");
            MeliResponse resp = client.get("/users/" + userId + "/items/search", params, null);
            JsonNode data = resp.data();
            if (mlTotal == null) mlTotal = data == null ? 0 : data.path("paging").path("total").asInt(0);

            List<String> batchIds = textList(data == null ? null : data.path("results"));
            if (batchIds.isEmpty()) break;

            for (JsonNode item : client.multiGetItems(batchIds, List.of("id", "status"), null)) {
                if (!NON_PENDING.contains(item.path("status").asText(""))) count++;
            }

            scanOffset += batchIds.size();
            if (mlTotal != null && scanOffset >= mlTotal) break;
        }
        log.info("count_pending user={}: {} pendentes (varridos {}/{})", userId, count, scanOffset, mlTotal);
        return count;
    }

    // ---- Detalhe -------------------------------------------------------------

    public JsonNode getItem(String itemId) {
        MeliResponse itemResp = client.get("/items/" + itemId + "?include_attributes=all", null);
        JsonNode itemNode = itemResp.data();
        if (!(itemNode instanceof ObjectNode item)) return itemNode;

        MeliResponse descResp = client.get("/items/" + itemId + "/description", null);
        JsonNode desc = descResp.status() == 200 ? descResp.data() : null;
        item.set("description", desc == null ? NullNode.getInstance() : desc);

        if (item.has("variations") && !item.path("variations").isEmpty()) {
            MeliResponse varResp = client.get("/items/" + itemId + "/variations", null);
            if (varResp.status() == 200 && varResp.data() != null) {
                item.set("variations_detail", varResp.data());
            }
        }
        return item;
    }

    // ---- Atualizações --------------------------------------------------------

    @Transactional
    public UpdateResult updateItem(String itemId, ObjectNode updates) {
        JsonNode sentAttrs = updates.path("attributes");
        if (sentAttrs.isArray() && !sentAttrs.isEmpty()) {
            log.info("update_item {} — atributos enviados: {}", itemId, describeAttrs(sentAttrs));
        }

        MeliResponse resp = client.put("/items/" + itemId, null, updates);
        int mlStatus = resp.status();
        JsonNode mlData = resp.data();

        List<String> ignored = new ArrayList<>();
        if (mlStatus != 200) {
            log.error("update_item {} — ML retornou {}: {}", itemId, mlStatus, mlData);
        } else if (sentAttrs.isArray() && mlData != null && mlData.isObject()) {
            ignored = detectIgnoredAttrs(itemId, sentAttrs, mlData.path("attributes"));
        }

        saveLog("update", itemId, updates, mlData,
                mlStatus == 200 ? "success" : "error",
                mlStatus == 200 ? null : String.valueOf(mlData));
        log.info("Item {} atualizado — status {}", itemId, mlStatus);
        return new UpdateResult(mlStatus, mlData, ignored);
    }

    @Transactional
    public UpdateResult updateStatus(String itemId, String newStatus) {
        ObjectNode updates = MAPPER.createObjectNode().put("status", newStatus);
        return updateItem(itemId, updates);
    }

    @Transactional
    public UpdateResult updatePictures(String itemId, JsonNode pictures) {
        ObjectNode updates = MAPPER.createObjectNode();
        updates.set("pictures", pictures);
        return updateItem(itemId, updates);
    }

    @Transactional
    public MeliResponse updateDescription(String itemId, String plainText) {
        ObjectNode body = MAPPER.createObjectNode().put("plain_text", plainText);
        MeliResponse resp = client.put("/items/" + itemId + "/description",
                Map.of("api_version", "2"), null, body);

        String snippet = plainText.length() > 200 ? plainText.substring(0, 200) + "..." : plainText;
        ObjectNode payload = MAPPER.createObjectNode().put("plain_text", snippet);
        saveLog("update_description", itemId, payload, resp.data(),
                resp.status() == 200 ? "success" : "error", null);
        return resp;
    }

    // ---- Internos ------------------------------------------------------------

    private List<String> detectIgnoredAttrs(String itemId, JsonNode sentAttrs, JsonNode returnedAttrs) {
        Map<String, JsonNode> returnedMap = new LinkedHashMap<>();
        if (returnedAttrs.isArray()) {
            for (JsonNode a : returnedAttrs) returnedMap.put(a.path("id").asText(null), a);
        }
        List<String> ignored = new ArrayList<>();
        for (JsonNode sent : sentAttrs) {
            String attrId = sent.path("id").asText(null);
            JsonNode returned = returnedMap.get(attrId);
            if (returned == null) {
                log.warn("update_item {} — atributo '{}' NÃO aparece na resposta do ML "
                        + "(possível rejeição silenciosa)", itemId, attrId);
                ignored.add(attrId);
                continue;
            }
            String sentVal = firstText(sent, "value_name", "value_id");
            String gotVal = firstText(returned, "value_name", "value_id");
            if (sentVal != null && !sentVal.equalsIgnoreCase(gotVal == null ? "" : gotVal)) {
                log.warn("update_item {} — atributo '{}' diverge: enviado={} ML-retornou={} "
                        + "(ML ignorou a alteração)", itemId, attrId, sentVal, gotVal);
                ignored.add(attrId);
            }
        }
        return ignored;
    }

    private void saveLog(String type, String itemId, JsonNode payload, JsonNode response,
                         String status, String errorMessage) {
        logs.save(new OperationLog(type, List.of(itemId), payload, response, status, errorMessage));
    }

    private static Map<String, Object> paging(JsonNode data) {
        if (data != null && data.has("paging")) {
            return MAPPER.convertValue(data.get("paging"), Map.class);
        }
        return Map.of();
    }

    private static List<String> textList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) arr.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static List<JsonNode> filterByTitle(List<JsonNode> items, String q) {
        String ql = q.toLowerCase();
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode i : items) {
            if (i.path("title").asText("").toLowerCase().contains(ql)) out.add(i);
        }
        return out;
    }

    private static String describeAttrs(JsonNode attrs) {
        List<String> parts = new ArrayList<>();
        for (JsonNode a : attrs) {
            String val = firstText(a, "value_name", "value_id", "value_struct");
            parts.add(a.path("id").asText("") + "=" + val);
        }
        return String.join(", ", parts);
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String f : fields) {
            JsonNode v = node.get(f);
            if (v != null && !v.isNull()) {
                String s = v.isValueNode() ? v.asText() : v.toString();
                if (!s.isEmpty()) return s;
            }
        }
        return null;
    }
}
