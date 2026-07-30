package com.myrias.mlmanager.meli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myrias.mlmanager.meli.MeliClient.MeliResponse;
import com.myrias.mlmanager.ops.OperationLog;
import com.myrias.mlmanager.ops.OperationLogRepository;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Perguntas do Mercado Livre agregadas entre contas. Espelho de meli_questions.py. */
@Service
public class MeliQuestionsService {

    private static final Logger log = LoggerFactory.getLogger(MeliQuestionsService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> ITEM_FIELDS = List.of(
            "id", "title", "thumbnail", "permalink", "seller_custom_field", "attributes");

    private final MeliClient client;
    private final MeliAuthService auth;
    private final OperationLogRepository logs;

    public MeliQuestionsService(MeliClient client, MeliAuthService auth, OperationLogRepository logs) {
        this.client = client;
        this.auth = auth;
        this.logs = logs;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> listQuestions(String status, int limitPerAccount, int answeredSinceDays) {
        List<Map<String, Object>> accounts = auth.listAccounts();
        if (accounts.isEmpty()) {
            return Map.of("questions", List.of(), "counts", Map.of(), "accounts", List.of());
        }

        List<JsonNode> flat = new ArrayList<>();
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> account : accounts) {
            long userId = number(account.get("user_id")).longValue();
            List<JsonNode> qs = fetchForAccount(userId, status, limitPerAccount);
            counts.put(userId, qs.size());
            for (JsonNode q : qs) {
                ObjectNode copy = q.deepCopy();
                copy.put("_account_user_id", userId);
                copy.put("_account_nickname", String.valueOf(account.get("nickname")));
                flat.add(copy);
            }
        }

        if ("ANSWERED".equals(status) && answeredSinceDays > 0) {
            LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(answeredSinceDays);
            flat = flat.stream()
                    .filter(q -> datePrefix(q.path("date_created")).compareTo(cutoff.toString()) >= 0)
                    .toList();
            counts = new LinkedHashMap<>();
            for (JsonNode q : flat) {
                long uid = q.path("_account_user_id").asLong();
                counts.put(uid, counts.getOrDefault(uid, 0) + 1);
            }
        }

        if (flat.isEmpty()) {
            return Map.of("questions", List.of(), "counts", counts, "accounts", accountSummaries(accounts));
        }

        Map<Long, Set<String>> itemsByAccount = new LinkedHashMap<>();
        Set<BuyerRequest> buyerRequests = new LinkedHashSet<>();
        for (JsonNode q : flat) {
            long uid = q.path("_account_user_id").asLong();
            String itemId = q.path("item_id").asText("");
            if (!itemId.isBlank()) itemsByAccount.computeIfAbsent(uid, k -> new LinkedHashSet<>()).add(itemId);
            Long buyerId = nullableLong(q.path("from").path("id"));
            if (buyerId != null) buyerRequests.add(new BuyerRequest(buyerId, uid));
        }

        Map<String, JsonNode> itemsById = new LinkedHashMap<>();
        for (Map.Entry<Long, Set<String>> e : itemsByAccount.entrySet()) {
            try {
                for (JsonNode item : client.multiGetItems(new ArrayList<>(e.getValue()), ITEM_FIELDS, e.getKey())) {
                    itemsById.put(item.path("id").asText(), item);
                }
            } catch (Exception ex) {
                log.warn("Falha ao carregar itens da conta {}: {}", e.getKey(), ex.getMessage());
            }
        }

        Map<Long, String> buyerMap = new LinkedHashMap<>();
        for (BuyerRequest req : buyerRequests) {
            String nick = getBuyerNickname(req.buyerId(), req.sellerUserId());
            if (nick != null) buyerMap.put(req.buyerId(), nick);
        }

        Map<String, List<Map<String, Object>>> historyByBuyerItem = new LinkedHashMap<>();
        if ("UNANSWERED".equals(status)) {
            historyByBuyerItem = loadHistory(flat);
        }

        List<Map<String, Object>> outQuestions = new ArrayList<>();
        for (JsonNode q : flat) {
            if (q.path("deleted_from_listing").asBoolean(false) || q.path("hold").asBoolean(false)) continue;
            String itemId = q.path("item_id").asText(null);
            JsonNode item = itemsById.getOrDefault(itemId == null ? "" : itemId, MAPPER.createObjectNode());
            Long buyerId = nullableLong(q.path("from").path("id"));

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", q.path("id").asLong());
            entry.put("text", q.path("text").asText(""));
            entry.put("status", q.path("status").asText(""));
            entry.put("date_created", nullIfMissing(q.path("date_created")));
            entry.put("answer", nullIfMissing(q.path("answer")));
            entry.put("deleted_from_listing", q.path("deleted_from_listing").asBoolean(false));
            entry.put("hold", q.path("hold").asBoolean(false));
            entry.put("item", itemMap(itemId, item));
            entry.put("buyer", buyerMap(buyerId, buyerId == null ? null : buyerMap.get(buyerId)));
            entry.put("account", Map.of(
                    "user_id", q.path("_account_user_id").asLong(),
                    "nickname", q.path("_account_nickname").asText("")));
            entry.put("history", historyByBuyerItem.getOrDefault(historyKey(buyerId, itemId), List.of()));
            outQuestions.add(entry);
        }

        // null vira "" pra ordenar como o Python (sem data vai pro fim da lista).
        outQuestions.sort((a, b) -> sortKey(b.get("date_created")).compareTo(sortKey(a.get("date_created"))));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("questions", outQuestions);
        out.put("counts", counts);
        out.put("accounts", accountSummaries(accounts));
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> stats(String period, int periods) {
        List<Map<String, Object>> accounts = auth.listAccounts();
        if (accounts.isEmpty()) return Map.of("period", period, "periods", periods, "accounts", List.of());
        List<Map<String, Object>> outAccounts = new ArrayList<>();
        for (Map<String, Object> account : accounts) {
            long userId = number(account.get("user_id")).longValue();
            List<Map<String, Object>> series = "month".equals(period)
                    ? aggregateMonthly(userId, periods)
                    : aggregateDaily(userId, periods);
            int total = series.stream().mapToInt(s -> ((Number) s.get("count")).intValue()).sum();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("user_id", userId);
            row.put("nickname", String.valueOf(account.get("nickname")));
            row.put("series", series);
            row.put("total", total);
            outAccounts.add(row);
        }
        return Map.of("period", period, "periods", periods, "accounts", outAccounts);
    }

    @Transactional
    public JsonNode answerQuestion(long questionId, String text, long sellerUserId,
                                   String itemId, String questionText) {
        String nickname = nicknameFor(sellerUserId);
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("question_id", questionId);
        payload.put("item_id", itemId);
        payload.put("question_text", questionText);
        payload.put("answer_text", text);
        payload.put("nickname", nickname);

        ObjectNode body = MAPPER.createObjectNode();
        body.put("question_id", questionId);
        body.put("text", text);
        try {
            MeliResponse resp = client.post("/answers", sellerUserId, body);
            if (resp.status() != 200 && resp.status() != 201) {
                throw new IllegalStateException("ML rejeitou resposta: " + resp.status() + " " + resp.data());
            }
            OperationLog op = new OperationLog("answer_question",
                    itemId == null || itemId.isBlank() ? null : List.of(itemId),
                    payload, resp.data(), "success", null);
            op.setUserId(sellerUserId);
            logs.save(op);
            return resp.data();
        } catch (Exception e) {
            OperationLog op = new OperationLog("answer_question",
                    itemId == null || itemId.isBlank() ? null : List.of(itemId),
                    payload, null, "error", e.getMessage());
            op.setUserId(sellerUserId);
            logs.save(op);
            throw e;
        }
    }

    private List<JsonNode> fetchForAccount(long userId, String status, int limit) {
        try {
            MeliResponse resp = client.get("/questions/search", Map.of(
                    "seller_id", String.valueOf(userId),
                    "status", status,
                    "limit", String.valueOf(Math.min(limit, 50)),
                    "api_version", "4",
                    "sort_fields", "date_created",
                    "sort_types", "DESC"), userId);
            return array(resp.data(), "questions", "results");
        } catch (Exception e) {
            log.warn("Questions falhou para user={}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    private String getBuyerNickname(long buyerId, long sellerUserId) {
        try {
            MeliResponse resp = client.get("/users/" + buyerId, sellerUserId);
            return resp.data() == null ? null : resp.data().path("nickname").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, List<Map<String, Object>>> loadHistory(List<JsonNode> flat) {
        Map<AccountItem, Set<Long>> accountItemMap = new LinkedHashMap<>();
        for (JsonNode q : flat) {
            String itemId = q.path("item_id").asText("");
            Long buyerId = nullableLong(q.path("from").path("id"));
            if (!itemId.isBlank() && buyerId != null) {
                accountItemMap.computeIfAbsent(new AccountItem(q.path("_account_user_id").asLong(), itemId),
                        k -> new LinkedHashSet<>()).add(buyerId);
            }
        }
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        for (Map.Entry<AccountItem, Set<Long>> e : accountItemMap.entrySet()) {
            try {
                MeliResponse resp = client.get("/questions/search", Map.of(
                        "seller_id", String.valueOf(e.getKey().userId()),
                        "item", e.getKey().itemId(),
                        "status", "ANSWERED",
                        "limit", "10",
                        "api_version", "4",
                        "sort_fields", "date_created",
                        "sort_types", "DESC"), e.getKey().userId());
                for (JsonNode hq : array(resp.data(), "questions", "results")) {
                    Long bid = nullableLong(hq.path("from").path("id"));
                    if (bid == null || !e.getValue().contains(bid)) continue;
                    Map<String, Object> h = new LinkedHashMap<>();
                    h.put("id", hq.path("id").asLong());
                    h.put("text", hq.path("text").asText(""));
                    h.put("date_created", nullIfMissing(hq.path("date_created")));
                    h.put("answer", nullIfMissing(hq.path("answer")));
                    out.computeIfAbsent(historyKey(bid, e.getKey().itemId()), k -> new ArrayList<>()).add(h);
                }
            } catch (Exception ex) {
                log.warn("Historico perguntas falhou account={} item={}: {}",
                        e.getKey().userId(), e.getKey().itemId(), ex.getMessage());
            }
        }
        return out;
    }

    private List<String> fetchQuestionsSince(long userId, LocalDate since, int maxOffset) {
        List<String> out = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        int page = 50;
        int offset = 0;
        while (offset <= maxOffset) {
            MeliResponse resp;
            try {
                resp = client.get("/questions/search", Map.of(
                        "seller_id", String.valueOf(userId),
                        "sort_fields", "date_created",
                        "sort_types", "DESC",
                        "limit", String.valueOf(page),
                        "offset", String.valueOf(offset),
                        "api_version", "4"), userId);
            } catch (Exception e) {
                log.warn("Aggregate questions falhou user={}: {}", userId, e.getMessage());
                break;
            }
            List<JsonNode> results = array(resp.data(), "questions", "results");
            if (results.isEmpty()) break;
            boolean crossed = false;
            for (JsonNode q : results) {
                Long qid = nullableLong(q.path("id"));
                if (qid != null && !seen.add(qid)) continue;
                String created = q.path("date_created").asText("");
                String day = datePrefix(q.path("date_created"));
                if (day.length() != 10) continue;
                if (day.compareTo(since.toString()) >= 0) {
                    out.add(created);
                } else {
                    crossed = true;
                }
            }
            offset += page;
            if (crossed) break;
        }
        return out;
    }

    private List<Map<String, Object>> aggregateDaily(long userId, int days) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate start = today.minusDays(days - 1L);
        List<String> raw = fetchQuestionsSince(userId, start, 950);
        Map<String, Integer> daily = new LinkedHashMap<>();
        for (String r : raw) daily.put(r.substring(0, Math.min(10, r.length())),
                daily.getOrDefault(r.substring(0, Math.min(10, r.length())), 0) + 1);
        List<Map<String, Object>> out = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(today); d = d.plusDays(1)) {
            out.add(Map.of("label", d.toString(), "count", daily.getOrDefault(d.toString(), 0)));
        }
        return out;
    }

    private List<Map<String, Object>> aggregateMonthly(long userId, int months) {
        YearMonth current = YearMonth.now(ZoneOffset.UTC);
        YearMonth start = current.minusMonths(months - 1L);
        List<String> raw = fetchQuestionsSince(userId, start.atDay(1), 950);
        Map<String, Integer> monthly = new LinkedHashMap<>();
        for (String r : raw) {
            if (r.length() >= 7) monthly.put(r.substring(0, 7), monthly.getOrDefault(r.substring(0, 7), 0) + 1);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        YearMonth cursor = start;
        for (int i = 0; i < months; i++) {
            out.add(Map.of("label", cursor.toString(), "count", monthly.getOrDefault(cursor.toString(), 0)));
            cursor = cursor.plusMonths(1);
        }
        return out;
    }

    private String nicknameFor(long userId) {
        for (Map<String, Object> account : auth.listAccounts()) {
            if (number(account.get("user_id")).longValue() == userId) return String.valueOf(account.get("nickname"));
        }
        return "Conta " + userId;
    }

    private static List<JsonNode> array(JsonNode data, String... names) {
        if (data == null) return List.of();
        for (String name : names) {
            JsonNode arr = data.get(name);
            if (arr != null && arr.isArray()) {
                List<JsonNode> out = new ArrayList<>();
                arr.forEach(out::add);
                return out;
            }
        }
        if (data.isArray()) {
            List<JsonNode> out = new ArrayList<>();
            data.forEach(out::add);
            return out;
        }
        return List.of();
    }

    private static Map<String, Object> itemMap(String itemId, JsonNode item) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", itemId);
        out.put("title", nullIfMissing(item.path("title")));
        out.put("thumbnail", nullIfMissing(item.path("thumbnail")));
        out.put("permalink", nullIfMissing(item.path("permalink")));
        out.put("sku", extractSku(item));
        return out;
    }

    private static Map<String, Object> buyerMap(Long buyerId, String nickname) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", buyerId);
        out.put("nickname", nickname);
        return out;
    }

    private static List<Map<String, Object>> accountSummaries(List<Map<String, Object>> accounts) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> a : accounts) {
            out.add(Map.of("user_id", a.get("user_id"), "nickname", a.get("nickname")));
        }
        return out;
    }

    private static String extractSku(JsonNode item) {
        if (item == null || item.isMissingNode()) return null;
        if (item.hasNonNull("seller_custom_field")) return item.path("seller_custom_field").asText();
        for (JsonNode attr : item.path("attributes")) {
            if ("SELLER_SKU".equals(attr.path("id").asText()) && attr.hasNonNull("value_name")) {
                return attr.path("value_name").asText();
            }
        }
        return null;
    }

    private static String sortKey(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String historyKey(Long buyerId, String itemId) {
        return String.valueOf(buyerId) + "|" + String.valueOf(itemId);
    }

    private static String datePrefix(JsonNode node) {
        String s = node.asText("");
        return s.length() >= 10 ? s.substring(0, 10) : "";
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

    private record BuyerRequest(long buyerId, long sellerUserId) {}
    private record AccountItem(long userId, String itemId) {}
}
