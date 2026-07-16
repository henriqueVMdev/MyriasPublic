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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Mensagens pos-venda agregadas entre contas. Espelho de meli_messages.py. */
@Service
public class MeliMessagesService {

    private static final Logger log = LoggerFactory.getLogger(MeliMessagesService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern PACK_PATTERN = Pattern.compile("/packs/(\\d+)");
    private static final Pattern SELLER_PATTERN = Pattern.compile("/sellers/(\\d+)");

    private final MeliClient client;
    private final MeliAuthService auth;
    private final OperationLogRepository logs;

    public MeliMessagesService(MeliClient client, MeliAuthService auth, OperationLogRepository logs) {
        this.client = client;
        this.auth = auth;
        this.logs = logs;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> listUnread(int limitPerAccount, boolean enrichOrders) {
        List<Map<String, Object>> accounts = auth.listAccounts();
        if (accounts.isEmpty()) return Map.of("conversations", List.of(), "counts", Map.of(), "accounts", List.of());

        Map<String, Map<String, Object>> conversations = new LinkedHashMap<>();
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> account : accounts) {
            long userId = number(account.get("user_id")).longValue();
            counts.put(userId, 0);
            for (JsonNode item : fetchUnreadForAccount(userId, limitPerAccount)) {
                String packId = extractPackId(item);
                if (packId == null) continue;
                String key = userId + "|" + packId;
                String date = lastDate(item);
                int inc = unreadCount(item);
                Map<String, Object> existing = conversations.get(key);
                if (existing == null) {
                    Map<String, Object> conv = new LinkedHashMap<>();
                    conv.put("pack_id", packId);
                    conv.put("buyer_id", extractBuyerId(item, userId));
                    conv.put("buyer_nickname", null);
                    conv.put("last_text", lastText(item));
                    conv.put("last_date", date);
                    conv.put("unread_count", inc);
                    conv.put("items", new ArrayList<Map<String, Object>>());
                    conv.put("account", Map.of("user_id", userId, "nickname", account.get("nickname")));
                    conversations.put(key, conv);
                } else {
                    existing.put("unread_count", ((Number) existing.get("unread_count")).intValue() + inc);
                    Object oldDate = existing.get("last_date");
                    if (date != null && (oldDate == null || date.compareTo(String.valueOf(oldDate)) > 0)) {
                        existing.put("last_date", date);
                        String text = lastText(item);
                        if (!text.isBlank()) existing.put("last_text", text);
                    }
                    if (existing.get("buyer_id") == null) existing.put("buyer_id", extractBuyerId(item, userId));
                }
                counts.put(userId, counts.getOrDefault(userId, 0) + inc);
            }
        }

        List<Map<String, Object>> out = new ArrayList<>(conversations.values());
        for (Map<String, Object> conv : out) {
            if (String.valueOf(conv.get("last_text")).isBlank()) enrichPreview(conv);
            if (enrichOrders) enrichOrder(conv);
        }
        // null vira "" pra ordenar como o Python (conversas sem data vao pro fim).
        out.sort((a, b) -> sortKey(b.get("last_date")).compareTo(sortKey(a.get("last_date"))));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversations", out);
        result.put("counts", counts);
        result.put("accounts", accountSummaries(accounts));
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getThread(String packId, long sellerUserId, boolean markAsRead) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("tag", "post_sale");
        if (!markAsRead) params.put("mark_as_read", "false");
        try {
            MeliResponse resp = client.get("/messages/packs/" + packId + "/sellers/" + sellerUserId,
                    params, sellerUserId);
            if (resp.status() != 200 && resp.status() != 201) {
                return threadError(packId, sellerUserId, "ML retornou " + resp.status());
            }
            List<Map<String, Object>> messages = parseMessages(resp.data(), sellerUserId);
            Long buyerId = null;
            for (Map<String, Object> m : messages) {
                for (String field : List.of("from_user_id", "to_user_id")) {
                    Object v = m.get(field);
                    if (v instanceof Number n && n.longValue() != sellerUserId) {
                        buyerId = n.longValue();
                        break;
                    }
                }
                if (buyerId != null) break;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("pack_id", packId);
            out.put("seller_user_id", sellerUserId);
            out.put("buyer_id", buyerId);
            out.put("messages", messages);
            return out;
        } catch (Exception e) {
            log.warn("Get thread falhou pack={} user={}: {}", packId, sellerUserId, e.getMessage());
            return threadError(packId, sellerUserId, e.getMessage());
        }
    }

    @Transactional
    public JsonNode sendMessage(String packId, long sellerUserId, long buyerUserId, String text) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("pack_id", packId);
        payload.put("buyer_user_id", buyerUserId);
        payload.put("text", text);
        payload.put("nickname", nicknameFor(sellerUserId));

        ObjectNode body = MAPPER.createObjectNode();
        body.set("from", MAPPER.createObjectNode().put("user_id", String.valueOf(sellerUserId)));
        ArrayNode to = MAPPER.createArrayNode();
        to.add(MAPPER.createObjectNode().put("user_id", String.valueOf(buyerUserId)));
        body.set("to", to);
        body.put("text", text);

        try {
            MeliResponse resp = client.post("/messages/packs/" + packId + "/sellers/" + sellerUserId,
                    Map.of("tag", "post_sale"), sellerUserId, body);
            if (resp.status() != 200 && resp.status() != 201) {
                throw new IllegalStateException("ML rejeitou envio: " + resp.status() + " " + resp.data());
            }
            OperationLog op = new OperationLog("send_message", null, payload, resp.data(), "success", null);
            op.setUserId(sellerUserId);
            logs.save(op);
            return resp.data();
        } catch (Exception e) {
            OperationLog op = new OperationLog("send_message", null, payload, null, "error", e.getMessage());
            op.setUserId(sellerUserId);
            logs.save(op);
            throw e;
        }
    }

    private List<JsonNode> fetchUnreadForAccount(long userId, int limit) {
        try {
            MeliResponse resp = client.get("/messages/unread", Map.of(
                    "role", "seller",
                    "tag", "post_sale",
                    "limit", String.valueOf(Math.min(limit, 50))), userId);
            JsonNode results = resp.data() == null ? null : resp.data().path("results");
            if (results != null && results.isArray()) {
                List<JsonNode> out = new ArrayList<>();
                results.forEach(out::add);
                return out;
            }
        } catch (Exception e) {
            log.warn("Messages unread falhou para user={}: {}", userId, e.getMessage());
        }
        return List.of();
    }

    private void enrichPreview(Map<String, Object> conv) {
        @SuppressWarnings("unchecked")
        Map<String, Object> account = (Map<String, Object>) conv.get("account");
        Map<String, Object> thread = getThread(String.valueOf(conv.get("pack_id")),
                number(account.get("user_id")).longValue(), false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) thread.get("messages");
        if (messages == null || messages.isEmpty()) return;
        Map<String, Object> last = messages.get(messages.size() - 1);
        conv.put("last_text", last.get("text"));
        conv.put("last_date", last.get("date"));
        if (conv.get("buyer_id") == null) conv.put("buyer_id", thread.get("buyer_id"));
    }

    @SuppressWarnings("unchecked")
    private void enrichOrder(Map<String, Object> conv) {
        Map<String, Object> account = (Map<String, Object>) conv.get("account");
        long sellerUid = number(account.get("user_id")).longValue();
        String packId = String.valueOf(conv.get("pack_id"));
        try {
            List<JsonNode> orders = ordersForPack(packId, sellerUid);
            if (orders.isEmpty()) return;
            JsonNode buyer = orders.get(0).path("buyer");
            if (buyer.hasNonNull("nickname")) conv.put("buyer_nickname", buyer.path("nickname").asText());
            if (conv.get("buyer_id") == null && buyer.hasNonNull("id")) conv.put("buyer_id", buyer.path("id").asLong());

            List<Map<String, Object>> items = new ArrayList<>();
            List<String> itemIds = new ArrayList<>();
            for (JsonNode order : orders) {
                for (JsonNode oi : order.path("order_items")) {
                    JsonNode it = oi.path("item");
                    String itemId = it.path("id").asText(null);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("item_id", itemId);
                    row.put("title", it.path("title").asText(""));
                    row.put("sku", firstText(it, "seller_sku", "seller_custom_field"));
                    row.put("quantity", oi.path("quantity").asInt(1));
                    row.put("thumbnail", null);
                    row.put("permalink", null);
                    items.add(row);
                    if (itemId != null && !itemId.isBlank()) itemIds.add(itemId);
                }
            }
            if (!itemIds.isEmpty()) {
                Map<String, JsonNode> byId = new LinkedHashMap<>();
                for (JsonNode item : client.multiGetItems(itemIds,
                        List.of("id", "thumbnail", "secure_thumbnail", "permalink"), sellerUid)) {
                    byId.put(item.path("id").asText(), item);
                }
                for (Map<String, Object> item : items) {
                    JsonNode fetched = byId.get(String.valueOf(item.get("item_id")));
                    if (fetched != null) {
                        item.put("thumbnail", firstText(fetched, "secure_thumbnail", "thumbnail"));
                        item.put("permalink", nullIfMissing(fetched.path("permalink")));
                    }
                }
            }
            conv.put("items", items);
        } catch (Exception e) {
            log.warn("Enriquecer pedido pack={} falhou: {}", packId, e.getMessage());
        }
    }

    private List<JsonNode> ordersForPack(String packId, long sellerUid) {
        try {
            MeliResponse pack = client.get("/packs/" + packId, sellerUid);
            if (pack.status() == 200 && pack.data() != null) {
                List<String> ids = new ArrayList<>();
                for (JsonNode ref : pack.data().path("orders")) {
                    if (ref.hasNonNull("id")) ids.add(ref.path("id").asText());
                }
                List<JsonNode> orders = new ArrayList<>();
                for (String id : ids) {
                    JsonNode order = getOrder(id, sellerUid);
                    if (order != null) orders.add(order);
                }
                if (!orders.isEmpty()) return orders;
            }
        } catch (Exception e) {
            log.warn("pack {} falhou: {}", packId, e.getMessage());
        }
        JsonNode direct = getOrder(packId, sellerUid);
        return direct == null ? List.of() : List.of(direct);
    }

    private JsonNode getOrder(String orderId, long sellerUid) {
        try {
            MeliResponse resp = client.get("/orders/" + orderId, sellerUid);
            return resp.status() == 200 ? resp.data() : null;
        } catch (Exception e) {
            log.warn("get_order {} falhou: {}", orderId, e.getMessage());
            return null;
        }
    }

    private static List<Map<String, Object>> parseMessages(JsonNode data, long sellerUserId) {
        List<JsonNode> raw = new ArrayList<>();
        JsonNode arr = data == null ? null : data.path("messages");
        if (arr == null || !arr.isArray() || arr.isEmpty()) arr = data == null ? null : data.path("results");
        if (arr != null && arr.isArray()) arr.forEach(raw::add);

        List<Map<String, Object>> messages = new ArrayList<>();
        for (JsonNode m : raw) {
            Long fromId = nullableLong(m.path("from").path("user_id"));
            JsonNode toField = m.path("to");
            JsonNode toObj = toField.isArray() && !toField.isEmpty() ? toField.get(0) : toField;
            Long toId = nullableLong(toObj.path("user_id"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", m.path("id").asText(""));
            row.put("text", m.path("text").asText(""));
            row.put("date", messageDate(m));
            row.put("from_user_id", fromId);
            row.put("to_user_id", toId);
            row.put("is_seller", fromId != null && fromId == sellerUserId);
            row.put("status", nullIfMissing(m.path("status")));
            messages.add(row);
        }
        messages.sort((a, b) -> sortKey(a.get("date")).compareTo(sortKey(b.get("date"))));
        return messages;
    }

    private static String sortKey(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Map<String, Object> threadError(String packId, long sellerUserId, String error) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pack_id", packId);
        out.put("seller_user_id", sellerUserId);
        out.put("buyer_id", null);
        out.put("messages", List.of());
        out.put("error", error);
        return out;
    }

    private static String extractPackId(JsonNode item) {
        String resource = item.path("resource").asText(null);
        if (resource != null) {
            Matcher m = PACK_PATTERN.matcher(resource);
            if (m.find()) return m.group(1);
            if ("packs".equals(resource) && item.hasNonNull("resource_id")) return item.path("resource_id").asText();
        }
        for (JsonNode res : item.path("message_resources")) {
            if ("packs".equals(res.path("name").asText())) return res.path("id").asText(null);
        }
        return null;
    }

    private static Long extractSellerIdFromResource(JsonNode item) {
        String resource = item.path("resource").asText(null);
        if (resource == null) return null;
        Matcher m = SELLER_PATTERN.matcher(resource);
        return m.find() ? Long.parseLong(m.group(1)) : null;
    }

    private static JsonNode lastMessageObj(JsonNode item) {
        return item.path("last_message").isObject() ? item.path("last_message") : item;
    }

    private static String lastText(JsonNode item) {
        return lastMessageObj(item).path("text").asText("");
    }

    private static String lastDate(JsonNode item) {
        return messageDate(lastMessageObj(item));
    }

    private static String messageDate(JsonNode msg) {
        JsonNode date = msg.path("message_date");
        if (date.isObject()) {
            String v = firstText(date, "received", "created", "available");
            if (v != null) return v;
        }
        return msg.path("date_created").asText(null);
    }

    private static Long extractBuyerId(JsonNode item, long sellerUserId) {
        JsonNode msg = lastMessageObj(item);
        List<Long> candidates = new ArrayList<>();
        Long fromId = nullableLong(msg.path("from").path("user_id"));
        if (fromId != null) candidates.add(fromId);
        JsonNode to = msg.path("to");
        if (to.isArray()) {
            for (JsonNode t : to) {
                Long id = nullableLong(t.path("user_id"));
                if (id != null) candidates.add(id);
            }
        } else {
            Long id = nullableLong(to.path("user_id"));
            if (id != null) candidates.add(id);
        }
        Long sellerFromResource = extractSellerIdFromResource(item);
        long seller = sellerFromResource == null ? sellerUserId : sellerFromResource;
        for (Long c : candidates) if (c != seller) return c;
        return null;
    }

    private static int unreadCount(JsonNode item) {
        if (item.path("count").isInt() && item.path("count").asInt() > 0) return item.path("count").asInt();
        if (item.path("msgs").isInt() && item.path("msgs").asInt() > 0) return item.path("msgs").asInt();
        return 1;
    }

    private String nicknameFor(long userId) {
        for (Map<String, Object> account : auth.listAccounts()) {
            if (number(account.get("user_id")).longValue() == userId) return String.valueOf(account.get("nickname"));
        }
        return "Conta " + userId;
    }

    private static List<Map<String, Object>> accountSummaries(List<Map<String, Object>> accounts) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> a : accounts) {
            out.add(Map.of("user_id", a.get("user_id"), "nickname", a.get("nickname")));
        }
        return out;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String f : fields) {
            JsonNode v = node.get(f);
            if (v != null && !v.isNull() && !v.asText("").isBlank()) return v.asText();
        }
        return null;
    }

    private static Long nullableLong(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isNumber()) return node.asLong();
        try {
            String s = node.asText();
            return s == null || s.isBlank() ? null : Long.parseLong(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static Object nullIfMissing(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node;
    }

    private static Number number(Object value) {
        if (value instanceof Number n) return n;
        return Long.parseLong(String.valueOf(value));
    }
}
