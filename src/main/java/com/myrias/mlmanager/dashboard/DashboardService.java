package com.hrb.mlmanager.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.hrb.mlmanager.meli.MeliAuthService;
import com.hrb.mlmanager.meli.MeliClient;
import com.hrb.mlmanager.meli.MeliClient.MeliResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Métricas do dashboard (contadores, faturamento, reputação, vendas, conversão).
 * Espelho de backend/app/api/dashboard.py.
 *
 * O {@code asyncio.gather} sobre contas vira {@link MeliClient#parallelMap}; a
 * vazão real continua limitada pelo rate limiter dentro de cada chamada ao ML.
 * Os intervalos de data são derivados em UTC, como no Python, e os filtros de
 * order-search usam strings ISO com milissegundos (mesmo formato do meli_performance).
 */
@Service
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    // Limites "permitidos" pelo ML para manter o nível verde (valores fixos).
    private static final double LIMIT_CLAIMS = 0.01;          // 1%
    private static final double LIMIT_MEDIATIONS = 0.005;     // 0.5%
    private static final double LIMIT_CANCELLATIONS = 0.005;  // 0.5%
    private static final double LIMIT_DELAYED = 0.06;         // 6%

    private static final int ORDERS_SAFETY_CAP = 2000;
    private static final int QUESTIONS_MAX_OFFSET = 950; // teto de offset do ML (~1000) menos a página

    private final MeliAuthService auth;
    private final MeliClient client;

    public DashboardService(MeliAuthService auth, MeliClient client) {
        this.auth = auth;
        this.client = client;
    }

    /** Intervalo resolvido (days OU from/to), já com clamp de 90 dias. */
    public record Range(int days, LocalDate start, LocalDate end) {}

    // ================= /summary =================

    public Map<String, Object> summary(String accounts) {
        List<Map<String, Object>> all = auth.listAccounts();
        if (all.isEmpty()) {
            return Map.of("error", "Não autenticado", "authenticated", false);
        }

        List<Map<String, Object>> scope;
        if ("all".equals(accounts)) {
            scope = all;
        } else {
            Set<Long> wanted = new HashSet<>();
            for (String part : accounts.split(",")) {
                String t = part.trim();
                if (t.matches("\\d+")) wanted.add(Long.parseLong(t));
            }
            List<Map<String, Object>> filtered = new ArrayList<>();
            for (Map<String, Object> a : all) if (wanted.contains(asLong(a.get("user_id")))) filtered.add(a);
            scope = filtered.isEmpty() ? all : filtered;
        }

        List<Map<String, Integer>> per = client.parallelMap(scope, a -> countsFor(asLong(a.get("user_id"))));
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String k : List.of("active", "paused", "closed", "total", "pending")) counts.put(k, 0);
        for (Map<String, Integer> c : per) {
            for (String k : List.of("active", "paused", "closed", "total", "pending")) {
                counts.put(k, asInt(counts.get(k)) + c.getOrDefault(k, 0));
            }
        }

        List<Long> userIds = new ArrayList<>();
        for (Map<String, Object> a : scope) userIds.add(asLong(a.get("user_id")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("authenticated", true);
        out.put("user_ids", userIds);
        out.put("counts", counts);
        return out;
    }

    private Map<String, Integer> countsFor(long uid) {
        // Tudo com limit=0 (instantâneo — só o paging.total); pendentes por aritmética.
        int active = pagingTotal(searchCount(uid, "active"));
        int paused = pagingTotal(searchCount(uid, "paused"));
        int closed = pagingTotal(searchCount(uid, "closed"));
        int total = pagingTotal(searchCount(uid, null));
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("active", active);
        m.put("paused", paused);
        m.put("closed", closed);
        m.put("total", total);
        m.put("pending", Math.max(0, total - active - paused - closed));
        return m;
    }

    private MeliResponse searchCount(long uid, String status) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("limit", "0");
        if (status != null) params.put("status", status);
        return client.get("/users/" + uid + "/items/search", params, uid);
    }

    // ================= /revenue =================

    public Map<String, Object> revenue(Range range) {
        List<Map<String, Object>> accounts = auth.listAccounts();
        List<String> dates = dateRange(range.start(), range.end());

        if (accounts.isEmpty()) {
            Map<String, Object> out = baseRange(range);
            out.put("dates", List.of());
            out.put("accounts", List.of());
            out.put("combined", List.of());
            out.put("combined_total", 0);
            return out;
        }

        int daysInPeriod = Math.max(1, dates.size());
        List<Map<String, Object>> perAccount = client.parallelMap(accounts, acc -> {
            long uid = asLong(acc.get("user_id"));
            DailyAgg agg = collectDailyRevenue(uid, range.start(), range.end());
            List<Double> series = new ArrayList<>();
            for (String d : dates) series.add(round2(agg.revenue.getOrDefault(d, 0.0)));
            double total = round2(series.stream().mapToDouble(Double::doubleValue).sum());
            int totalOrders = agg.orders.values().stream().mapToInt(Integer::intValue).sum();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("user_id", uid);
            m.put("nickname", acc.get("nickname"));
            m.put("series", series);
            m.put("total", total);
            m.put("total_orders", totalOrders);
            m.put("avg_ticket", totalOrders > 0 ? round2(total / totalOrders) : 0.0);
            m.put("avg_orders_per_day", round2((double) totalOrders / daysInPeriod));
            m.put("avg_revenue_per_day", round2(total / daysInPeriod));
            return m;
        });

        List<Double> combined = new ArrayList<>();
        for (int i = 0; i < dates.size(); i++) {
            double sum = 0;
            for (Map<String, Object> a : perAccount) sum += asDbl(seriesAt(a, "series", i));
            combined.add(round2(sum));
        }
        double combinedTotal = round2(combined.stream().mapToDouble(Double::doubleValue).sum());
        int combinedOrders = perAccount.stream().mapToInt(a -> asInt(a.get("total_orders"))).sum();
        int daysIn = Math.max(1, dates.size());

        Map<String, Object> out = baseRange(range);
        out.put("dates", dates);
        out.put("accounts", perAccount);
        out.put("combined", combined);
        out.put("combined_total", combinedTotal);
        out.put("combined_total_orders", combinedOrders);
        out.put("combined_avg_ticket", combinedOrders > 0 ? round2(combinedTotal / combinedOrders) : 0.0);
        out.put("combined_avg_orders_per_day", round2((double) combinedOrders / daysIn));
        out.put("combined_avg_revenue_per_day", round2(combinedTotal / daysIn));
        return out;
    }

    // ================= /performance =================

    public Map<String, Object> performance(Range range) {
        List<Map<String, Object>> accounts = auth.listAccounts();
        List<String> dates = dateRange(range.start(), range.end());

        if (accounts.isEmpty()) {
            Map<String, Object> out = baseRange(range);
            out.put("dates", List.of());
            out.put("accounts", List.of());
            out.put("combined", emptyCombinedPerf());
            return out;
        }

        List<Map<String, Object>> perAccount = client.parallelMap(accounts, acc -> {
            long uid = asLong(acc.get("user_id"));
            Map<String, Integer> visitsMap = collectVisits(uid, range.start(), range.end());
            DailyAgg revenue = collectDailyRevenue(uid, range.start(), range.end());
            Map<String, Integer> questionsMap = collectQuestionsDaily(uid, range.start());

            List<Integer> visitsSeries = seriesFrom(visitsMap, dates);
            List<Integer> salesSeries = seriesFrom(revenue.orders, dates); // contagem de pedidos/dia
            List<Integer> questionsSeries = seriesFrom(questionsMap, dates);
            int totalVisits = sum(visitsSeries), totalSales = sum(salesSeries), totalQuestions = sum(questionsSeries);
            log.info("Performance user={}: visits={} sales={} questions={}", uid, totalVisits, totalSales, totalQuestions);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("user_id", uid);
            m.put("nickname", acc.get("nickname"));
            m.put("visits_series", visitsSeries);
            m.put("sales_series", salesSeries);
            m.put("questions_series", questionsSeries);
            m.put("total_visits", totalVisits);
            m.put("total_sales", totalSales);
            m.put("total_questions", totalQuestions);
            m.put("sales_per_visit", safeRatio(totalSales, totalVisits));
            m.put("questions_per_visit", safeRatio(totalQuestions, totalVisits));
            m.put("sales_per_question", safeRatio(totalSales, totalQuestions));
            return m;
        });

        int n = dates.size();
        List<Integer> combinedVisits = combinedSeries(perAccount, "visits_series", n);
        List<Integer> combinedSales = combinedSeries(perAccount, "sales_series", n);
        List<Integer> combinedQuestions = combinedSeries(perAccount, "questions_series", n);
        int totalVisits = sum(combinedVisits), totalSales = sum(combinedSales), totalQuestions = sum(combinedQuestions);

        Map<String, Object> combined = new LinkedHashMap<>();
        combined.put("visits_series", combinedVisits);
        combined.put("sales_series", combinedSales);
        combined.put("questions_series", combinedQuestions);
        combined.put("total_visits", totalVisits);
        combined.put("total_sales", totalSales);
        combined.put("total_questions", totalQuestions);
        combined.put("sales_per_visit", safeRatio(totalSales, totalVisits));
        combined.put("questions_per_visit", safeRatio(totalQuestions, totalVisits));
        combined.put("sales_per_question", safeRatio(totalSales, totalQuestions));

        Map<String, Object> out = baseRange(range);
        out.put("dates", dates);
        out.put("accounts", perAccount);
        out.put("combined", combined);
        return out;
    }

    // ================= /sales =================

    public Map<String, Object> sales(int days) {
        List<Map<String, Object>> accounts = auth.listAccounts();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate start = today.minusDays(days - 1);

        if (accounts.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("days", days);
            out.put("items", List.of());
            out.put("total_quantity", 0);
            out.put("total_revenue", 0);
            out.put("accounts", List.of());
            return out;
        }

        List<List<JsonNode>> ordersPerAccount = client.parallelMap(accounts,
                acc -> fetchOrders(asLong(acc.get("user_id")), start, today));

        // Agrega por (conta, sku) — o prefixo da conta separa SKUs iguais entre contas.
        Map<String, Map<String, Object>> aggregate = new LinkedHashMap<>();
        for (int idx = 0; idx < accounts.size(); idx++) {
            Map<String, Object> acc = accounts.get(idx);
            long uid = asLong(acc.get("user_id"));
            for (JsonNode order : ordersPerAccount.get(idx)) {
                for (JsonNode oi : order.path("order_items")) {
                    JsonNode item = oi.path("item");
                    int qty = oi.path("quantity").asInt(0);
                    double unitPrice = oi.path("unit_price").asDouble(0);
                    String itemId = item.path("id").asText("");
                    String sku = firstNonEmpty(txt(item, "seller_sku"), txt(item, "seller_custom_field"),
                            itemId.isEmpty() ? "—" : itemId);
                    String title = item.path("title").asText("");
                    String key = uid + "|" + sku;
                    Map<String, Object> row = aggregate.computeIfAbsent(key, k -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("sku", sku);
                        m.put("item_id", itemId);
                        m.put("title", title);
                        m.put("quantity", 0);
                        m.put("revenue", 0.0);
                        Map<String, Object> account = new LinkedHashMap<>();
                        account.put("user_id", uid);
                        account.put("nickname", acc.get("nickname"));
                        m.put("account", account);
                        return m;
                    });
                    row.put("quantity", asInt(row.get("quantity")) + qty);
                    row.put("revenue", asDbl(row.get("revenue")) + qty * unitPrice);
                }
            }
        }

        List<Map<String, Object>> items = new ArrayList<>(aggregate.values());
        items.sort((a, b) -> Integer.compare(asInt(b.get("quantity")), asInt(a.get("quantity"))));
        int totalQuantity = items.stream().mapToInt(x -> asInt(x.get("quantity"))).sum();
        double totalRevenue = round2(items.stream().mapToDouble(x -> asDbl(x.get("revenue"))).sum());
        for (Map<String, Object> it : items) it.put("revenue", round2(asDbl(it.get("revenue"))));

        List<Map<String, Object>> accSummary = new ArrayList<>();
        for (Map<String, Object> a : accounts) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("user_id", asLong(a.get("user_id")));
            m.put("nickname", a.get("nickname"));
            accSummary.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", days);
        out.put("start", start.toString());
        out.put("end", today.toString());
        out.put("items", items);
        out.put("total_quantity", totalQuantity);
        out.put("total_revenue", totalRevenue);
        out.put("accounts", accSummary);
        return out;
    }

    // ================= /reputation =================

    public Map<String, Object> reputation() {
        List<Map<String, Object>> accounts = auth.listAccounts();
        if (accounts.isEmpty()) return Map.of("accounts", List.of());

        List<Map<String, Object>> data = client.parallelMap(accounts, acc -> {
            long uid = asLong(acc.get("user_id"));
            MeliResponse resp;
            try {
                resp = client.get("/users/" + uid, uid);
            } catch (Exception e) {
                log.warn("Reputation falhou user={}: {}", uid, e.getMessage());
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("user_id", uid);
                err.put("nickname", acc.get("nickname"));
                err.put("error", e.getMessage());
                return err;
            }
            JsonNode user = resp.data();
            JsonNode rep = user == null ? null : user.path("seller_reputation");
            JsonNode metrics = rep == null ? null : rep.path("metrics");

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("user_id", uid);
            m.put("nickname", acc.get("nickname"));
            m.put("level_id", rep == null ? null : txt(rep, "level_id"));
            m.put("power_seller_status", rep == null ? null : txt(rep, "power_seller_status"));
            m.put("sales_completed", metrics == null ? 0 : metrics.path("sales").path("completed").asInt(0));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("claims", metric(metrics, "claims", LIMIT_CLAIMS));
            out.put("mediations", metric(metrics, "mediations", LIMIT_MEDIATIONS));
            out.put("cancellations", metric(metrics, "cancellations", LIMIT_CANCELLATIONS));
            out.put("delayed_handling", metric(metrics, "delayed_handling_time", LIMIT_DELAYED));
            m.put("metrics", out);
            return m;
        });
        return Map.of("accounts", data);
    }

    private static Map<String, Object> metric(JsonNode metrics, String key, double limit) {
        JsonNode raw = metrics == null ? null : metrics.path(key);
        double rate = raw == null ? 0 : raw.path("rate").asDouble(0);
        int value = raw == null ? 0 : raw.path("value").asInt(0);
        String period = raw == null ? "" : raw.path("period").asText("");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rate", rate);
        m.put("value", value);
        m.put("period", period);
        m.put("limit", limit);
        m.put("within_limit", rate <= limit);
        return m;
    }

    // ================= coletores compartilhados =================

    /** Acumula faturamento e nº de pedidos por dia (ISO yyyy-mm-dd). */
    private DailyAgg collectDailyRevenue(long uid, LocalDate startDate, LocalDate endDate) {
        DailyAgg agg = new DailyAgg();
        String from = startDate + "T00:00:00.000Z";
        String to = endDate + "T23:59:59.999Z";
        int offset = 0;
        while (offset < ORDERS_SAFETY_CAP) {
            MeliResponse resp;
            try {
                resp = client.get("/orders/search", orderParams(uid, from, to, offset), uid);
            } catch (Exception e) {
                log.warn("Revenue: falha orders user={}: {}", uid, e.getMessage());
                break;
            }
            JsonNode data = resp.data();
            JsonNode results = data == null ? null : data.path("results");
            if (results == null || !results.isArray() || results.isEmpty()) break;
            for (JsonNode order : results) {
                String rawDate = firstNonEmpty(txt(order, "date_closed"), txt(order, "date_created"), "");
                if (rawDate.isEmpty()) continue;
                String day = rawDate.length() >= 10 ? rawDate.substring(0, 10) : rawDate;
                double amount = order.path("total_amount").asDouble(0);
                agg.revenue.merge(day, amount, Double::sum);
                agg.orders.merge(day, 1, Integer::sum);
            }
            int total = data.path("paging").path("total").asInt(0);
            offset += 50;
            if (offset >= total) break;
        }
        return agg;
    }

    /** Lista bruta de orders pagas no intervalo (usado pelo /sales). */
    private List<JsonNode> fetchOrders(long uid, LocalDate startDate, LocalDate endDate) {
        List<JsonNode> out = new ArrayList<>();
        String from = startDate + "T00:00:00.000Z";
        String to = endDate + "T23:59:59.999Z";
        int offset = 0;
        final int safety = 1000;
        while (offset < safety) {
            MeliResponse resp;
            try {
                resp = client.get("/orders/search", orderParams(uid, from, to, offset), uid);
            } catch (Exception e) {
                log.warn("Sales orders falhou user={}: {}", uid, e.getMessage());
                break;
            }
            JsonNode data = resp.data();
            JsonNode results = data == null ? null : data.path("results");
            if (results == null || !results.isArray() || results.isEmpty()) break;
            results.forEach(out::add);
            int total = data.path("paging").path("total").asInt(0);
            offset += 50;
            if (offset >= total) break;
        }
        return out;
    }

    private static Map<String, String> orderParams(long uid, String from, String to, int offset) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("seller", String.valueOf(uid));
        params.put("order.status", "paid");
        params.put("order.date_created.from", from);
        params.put("order.date_created.to", to);
        params.put("sort", "date_desc");
        params.put("limit", "50");
        params.put("offset", String.valueOf(offset));
        return params;
    }

    /** Visitas por dia via /users/{id}/items_visits/time_window (série diária). */
    private Map<String, Integer> collectVisits(long uid, LocalDate startDate, LocalDate endDate) {
        int last = (int) (endDate.toEpochDay() - startDate.toEpochDay()) + 1;
        Map<String, Integer> daily = new LinkedHashMap<>();
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("last", String.valueOf(last));
            params.put("unit", "day");
            params.put("ending", endDate.toString());
            MeliResponse resp = client.get("/users/" + uid + "/items_visits/time_window", params, uid);
            if (resp.status() != 200 || resp.data() == null) {
                log.warn("Visits user={}: status inesperado {}", uid, resp.status());
                return daily;
            }
            for (JsonNode entry : resp.data().path("results")) {
                String d = entry.path("date").asText("");
                if (d.length() >= 10) {
                    daily.merge(d.substring(0, 10), entry.path("total").asInt(0), Integer::sum);
                }
            }
        } catch (Exception e) {
            log.warn("Visits falhou user={}: {}", uid, e.getMessage());
        }
        return daily;
    }

    /** Perguntas por dia desde {@code sinceDate} (porta de _fetch_questions_since). */
    private Map<String, Integer> collectQuestionsDaily(long uid, LocalDate sinceDate) {
        Map<String, Integer> daily = new LinkedHashMap<>();
        String sinceDay = sinceDate.toString();
        Set<Long> seen = new HashSet<>();
        int offset = 0;
        final int page = 50;
        while (offset <= QUESTIONS_MAX_OFFSET) {
            MeliResponse resp;
            try {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("seller_id", String.valueOf(uid));
                params.put("sort_fields", "date_created");
                params.put("sort_types", "DESC");
                params.put("limit", String.valueOf(page));
                params.put("offset", String.valueOf(offset));
                params.put("api_version", "4");
                resp = client.get("/questions/search", params, uid);
            } catch (Exception e) {
                log.warn("Aggregate questions falhou user={}: {}", uid, e.getMessage());
                break;
            }
            JsonNode data = resp.data() == null ? null : resp.data();
            JsonNode results = data == null ? null : (data.has("questions") ? data.path("questions") : data.path("results"));
            if (results == null || !results.isArray() || results.isEmpty()) break;

            boolean crossed = false;
            for (JsonNode q : results) {
                if (q.hasNonNull("id")) {
                    long qid = q.path("id").asLong();
                    if (!seen.add(qid)) continue;
                }
                String d = q.path("date_created").asText("");
                if (d.length() < 10) continue;
                String day = d.substring(0, 10);
                if (day.compareTo(sinceDay) >= 0) {
                    daily.merge(day, 1, Integer::sum);
                } else {
                    crossed = true; // DESC: já passou pra trás da janela
                }
            }
            offset += page;
            if (crossed) break;
        }
        return daily;
    }

    // ================= helpers =================

    /** Resolve o intervalo (days OU from/to). Retorna null se from/to inválidos. */
    public Range resolveRange(int days, String from, String to) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (from != null && to != null) {
            LocalDate start, end;
            try {
                start = LocalDate.parse(from);
                end = LocalDate.parse(to);
            } catch (Exception e) {
                return null; // sinaliza "from/to devem ser YYYY-MM-DD"
            }
            if (start.isAfter(end)) {
                LocalDate tmp = start;
                start = end;
                end = tmp;
            }
            if (end.isAfter(today)) end = today;
            int delta = (int) (end.toEpochDay() - start.toEpochDay()) + 1;
            if (delta > 90) {
                start = end.minusDays(89);
                delta = 90;
            }
            if (delta < 1) delta = 1;
            return new Range(delta, start, end);
        }
        LocalDate end = today;
        LocalDate start = end.minusDays(days - 1);
        return new Range(days, start, end);
    }

    private static Map<String, Object> baseRange(Range range) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", range.days());
        out.put("from", range.start().toString());
        out.put("to", range.end().toString());
        return out;
    }

    private static Map<String, Object> emptyCombinedPerf() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("visits_series", List.of());
        c.put("sales_series", List.of());
        c.put("questions_series", List.of());
        c.put("total_visits", 0);
        c.put("total_sales", 0);
        c.put("total_questions", 0);
        c.put("sales_per_visit", 0.0);
        c.put("questions_per_visit", 0.0);
        c.put("sales_per_question", 0.0);
        return c;
    }

    private static List<String> dateRange(LocalDate from, LocalDate to) {
        List<String> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) out.add(d.toString());
        return out;
    }

    private static List<Integer> seriesFrom(Map<String, Integer> map, List<String> dates) {
        List<Integer> out = new ArrayList<>(dates.size());
        for (String d : dates) out.add(map.getOrDefault(d, 0));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> combinedSeries(List<Map<String, Object>> perAccount, String field, int n) {
        List<Integer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int sum = 0;
            for (Map<String, Object> a : perAccount) {
                List<Integer> series = (List<Integer>) a.get(field);
                if (series != null && i < series.size()) sum += series.get(i);
            }
            out.add(sum);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object seriesAt(Map<String, Object> account, String field, int i) {
        List<Object> series = (List<Object>) account.get(field);
        return series != null && i < series.size() ? series.get(i) : 0;
    }

    private static int pagingTotal(MeliResponse resp) {
        if (resp == null || resp.data() == null) return 0;
        return resp.data().path("paging").path("total").asInt(0);
    }

    private static double safeRatio(double num, double den) {
        return den > 0 ? round4(num / den) : 0.0;
    }

    private static int sum(List<Integer> xs) {
        int s = 0;
        for (int x : xs) s += x;
        return s;
    }

    private static String txt(JsonNode n, String f) {
        if (n == null) return null;
        JsonNode v = n.get(f);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static String firstNonEmpty(String... vals) {
        for (String v : vals) if (v != null && !v.isEmpty()) return v;
        return "";
    }

    private static int asInt(Object o) { return o instanceof Number n ? n.intValue() : 0; }
    private static long asLong(Object o) { return o instanceof Number n ? n.longValue() : 0L; }
    private static double asDbl(Object o) { return o instanceof Number n ? n.doubleValue() : 0.0; }
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }

    /** Faturamento e contagem de pedidos por dia. */
    private static final class DailyAgg {
        final Map<String, Double> revenue = new LinkedHashMap<>();
        final Map<String, Integer> orders = new LinkedHashMap<>();
    }
}
