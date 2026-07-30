package com.myrias.mlmanager.perf;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myrias.mlmanager.auth.PanelSecurity;
import com.myrias.mlmanager.meli.MeliAuthService;
import com.myrias.mlmanager.meli.MeliClient;
import com.myrias.mlmanager.ops.OperationLog;
import com.myrias.mlmanager.ops.OperationLogRepository;
import com.myrias.mlmanager.perf.MeliPerformanceService.DeleteResult;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Análise de performance dos anúncios. Espelho de backend/app/api/performance.py.
 * Lê dos snapshots ({@link MeliPerformanceService}); visitas/ads sob demanda só
 * no detalhe. O refresh pesado roda via {@link PerfRefreshRunner}.
 */
@RestController
@RequestMapping("/api/performance")
public class PerformanceController {

    private static final Logger log = LoggerFactory.getLogger(PerformanceController.class);
    private static final ObjectMapper M = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final long BIG = 1_000_000_000L;

    private final MeliPerformanceService svc;
    private final MeliClient client;
    private final MeliAuthService auth;
    private final PerfRefreshRunner runner;
    private final OperationLogRepository opLogs;
    private final PanelSecurity security;

    public PerformanceController(MeliPerformanceService svc, MeliClient client, MeliAuthService auth,
                                 PerfRefreshRunner runner, OperationLogRepository opLogs, PanelSecurity security) {
        this.svc = svc;
        this.client = client;
        this.auth = auth;
        this.runner = runner;
        this.opLogs = opLogs;
        this.security = security;
    }

    // ---------- snapshot-status / refresh ----------

    @GetMapping("/snapshot-status")
    public Map<String, Object> snapshotStatus() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> a : auth.listAccounts()) {
            long uid = asLong(a.get("user_id"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("user_id", uid);
            row.put("nickname", a.get("nickname"));
            row.put("refreshing", runner.isRunning(uid));
            row.putAll(svc.snapshotStatus(uid));
            out.add(row);
        }
        return Map.of("accounts", out);
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(
            @RequestParam(defaultValue = "all") String account,
            @RequestParam(defaultValue = "365") int lookback_days,
            @RequestParam(defaultValue = "0") int background,
            @RequestParam(defaultValue = "auto") String mode) {
        List<Map<String, Object>> accounts = accountsFor(account);
        if (accounts.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Nenhuma conta encontrada.");

        if (background != 0) {
            List<Long> started = new ArrayList<>();
            List<Long> already = new ArrayList<>();
            for (Map<String, Object> acc : accounts) {
                long uid = asLong(acc.get("user_id"));
                if (!runner.tryAcquire(uid)) {
                    already.add(uid);
                    continue;
                }
                log.info("perf: bg refresh START user={} ({}) mode={}", uid, acc.get("nickname"), mode);
                runner.runBackground(uid, str(acc.get("nickname")), lookback_days, mode);
                started.add(uid);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("started", started);
            out.put("already_running", already);
            return out;
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> acc : accounts) {
            long uid = asLong(acc.get("user_id"));
            log.info("perf: refresh user={} ({}) mode={}", uid, acc.get("nickname"), mode);
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("user_id", uid);
            res.put("nickname", acc.get("nickname"));
            res.putAll(svc.refreshSnapshot(uid, str(acc.get("nickname")), lookback_days, mode));
            results.add(res);
        }
        return Map.of("accounts", results);
    }

    // ---------- lista de performance ----------

    @GetMapping("/items")
    public Map<String, Object> listItems(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "all") String filter,
            @RequestParam(defaultValue = "60") int stale_days,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "all") String account,
            @RequestParam(defaultValue = "all") String logistic,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "90") int days,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {

        List<Map<String, Object>> accounts = accountsFor(account);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        BuildResult br = buildRows(accounts, today);
        List<Map<String, Object>> rows = br.rows;

        if (rows.isEmpty() && !br.missing.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("needs_refresh", true);
            out.put("missing_accounts", br.missing);
            out.put("items", List.of());
            out.put("summary", Map.of());
            out.put("paging", paging(0, offset, limit));
            return out;
        }

        long totalSold = rows.stream().mapToLong(r -> asInt(r.get("qty_sold"))).sum();
        Map<String, Object> byAds = byAds(adsTotalsFor(accounts), totalSold);

        if (q != null) {
            String ql = q.toLowerCase();
            rows = filter(rows, r -> str(r.get("title")).toLowerCase().contains(ql)
                    || str(r.get("sku")).toLowerCase().contains(ql));
        }
        if (status != null) {
            rows = filter(rows, r -> status.equals(r.get("status")));
        }

        int scopedTotal = rows.size();
        int semVendas = (int) rows.stream().filter(r -> (boolean) r.get("never_sold")).count();
        int parados = (int) rows.stream().filter(r -> !(boolean) r.get("never_sold")
                && r.get("days_since_last_sale") != null && asInt(r.get("days_since_last_sale")) >= stale_days).count();
        int comVendas = scopedTotal - semVendas;

        List<Map<String, Object>> topItems = new ArrayList<>(rows);
        topItems.sort(Comparator.comparingLong((Map<String, Object> r) -> asInt(r.get("sold_lifetime"))).reversed());
        List<Map<String, Object>> top5 = new ArrayList<>();
        for (Map<String, Object> r : topItems.subList(0, Math.min(5, topItems.size()))) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("id", r.get("id"));
            t.put("title", r.get("title"));
            t.put("sku", r.get("sku"));
            t.put("qty_sold", asInt(r.get("sold_lifetime")));
            top5.add(t);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", scopedTotal);
        summary.put("sem_vendas", semVendas);
        summary.put("com_vendas", comVendas);
        summary.put("parados", parados);
        summary.put("stale_days", stale_days);
        summary.put("top_items", top5);

        Map<String, Object> byModality = byModality(rows);

        if (List.of("full", "flex", "padrao").contains(logistic)) {
            rows = filter(rows, r -> modality(str(r.get("logistic_type"))).equals(logistic));
        }

        String defaultSort;
        switch (filter) {
            case "top_sellers" -> { rows = filter(rows, r -> asInt(r.get("sold_lifetime")) > 0); defaultSort = "sales_desc"; }
            case "no_sales" -> { rows = filter(rows, r -> (boolean) r.get("never_sold")); defaultSort = "price_desc"; }
            case "stale" -> {
                rows = filter(rows, r -> !(boolean) r.get("never_sold") && r.get("days_since_last_sale") != null
                        && asInt(r.get("days_since_last_sale")) >= stale_days);
                defaultSort = "last_sale_desc";
            }
            default -> defaultSort = "sales_desc";
        }

        String[] parsed = parseSort(sort == null ? defaultSort : sort, defaultSort);
        String field = parsed[0];
        boolean reverse = "desc".equals(parsed[1]);
        Comparator<Map<String, Object>> cmp = comparatorFor(field);
        rows.sort(reverse ? cmp.reversed() : cmp);
        String effectiveSort = field + "_" + (reverse ? "desc" : "asc");

        int total = rows.size();
        List<Map<String, Object>> page = rows.subList(Math.min(offset, total), Math.min(offset + limit, total));
        Map<String, Object> snap = accounts.isEmpty() ? Map.of()
                : svc.snapshotStatus(asLong(accounts.get(0).get("user_id")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("needs_refresh", false);
        out.put("missing_accounts", br.missing);
        out.put("items", page);
        out.put("summary", summary);
        out.put("by_modality", byModality);
        out.put("by_ads", byAds);
        out.put("snapshot", snap);
        out.put("sort", effectiveSort);
        out.put("paging", paging(total, offset, limit));
        return out;
    }

    @GetMapping("/duplicates")
    public Map<String, Object> duplicates(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "all") String account,
            @RequestParam(defaultValue = "2") int min_count,
            @RequestParam(defaultValue = "count_desc") String sort,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {

        List<Map<String, Object>> accounts = accountsFor(account);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        BuildResult br = buildRows(accounts, today);
        List<Map<String, Object>> rows = br.rows;

        if (rows.isEmpty() && !br.missing.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("needs_refresh", true);
            out.put("missing_accounts", br.missing);
            out.put("items", List.of());
            out.put("paging", paging(0, offset, limit));
            return out;
        }

        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String sku = str(r.get("sku")).trim();
            if (sku.isEmpty()) continue;
            Map<String, Object> g = groups.computeIfAbsent(sku, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("sku", sku);
                m.put("title", str(r.get("title")));
                m.put("thumbnail", r.get("thumbnail"));
                m.put("count", 0);
                m.put("qty_sold_total", 0);
                m.put("never_sold_count", 0);
                return m;
            });
            g.put("count", asInt(g.get("count")) + 1);
            g.put("qty_sold_total", asInt(g.get("qty_sold_total")) + asInt(r.get("qty_sold")));
            if ((boolean) r.get("never_sold")) g.put("never_sold_count", asInt(g.get("never_sold_count")) + 1);
            if (g.get("thumbnail") == null && r.get("thumbnail") != null) g.put("thumbnail", r.get("thumbnail"));
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> g : groups.values()) if (asInt(g.get("count")) >= min_count) items.add(g);

        if (q != null) {
            String ql = q.toLowerCase();
            items = filter(items, g -> str(g.get("sku")).toLowerCase().contains(ql)
                    || str(g.get("title")).toLowerCase().contains(ql));
        }

        boolean reverse = !"count_asc".equals(sort);
        Comparator<Map<String, Object>> cmp = Comparator
                .comparingInt((Map<String, Object> g) -> asInt(g.get("count")))
                .thenComparingInt(g -> asInt(g.get("never_sold_count")));
        items.sort(reverse ? cmp.reversed() : cmp);

        int total = items.size();
        List<Map<String, Object>> page = items.subList(Math.min(offset, total), Math.min(offset + limit, total));
        Map<String, Object> snap = accounts.isEmpty() ? Map.of()
                : svc.snapshotStatus(asLong(accounts.get(0).get("user_id")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("needs_refresh", false);
        out.put("missing_accounts", br.missing);
        out.put("items", page);
        out.put("snapshot", snap);
        out.put("sort", "count_asc".equals(sort) ? "count_asc" : "count_desc");
        out.put("paging", paging(total, offset, limit));
        return out;
    }

    @DeleteMapping("/items/{itemId}")
    public Map<String, Object> deleteItem(
            @PathVariable String itemId,
            @RequestParam(defaultValue = "false") boolean close_only,
            @RequestParam(required = false) String sku,
            @RequestParam(required = false) String batch_id,
            HttpServletRequest request) {
        security.require(request, "delete_listing");

        List<Map<String, Object>> accounts = accountsFor("all");
        Long ownerUid = null;
        String ownerNick = "";
        String title = null;
        for (Map<String, Object> acc : accounts) {
            long uid = asLong(acc.get("user_id"));
            JsonNode inv = svc.loadInventory(uid);
            if (inv == null) continue;
            for (JsonNode it : inv.path("items")) {
                if (itemId.equals(it.path("id").asText(null))) {
                    ownerUid = uid;
                    ownerNick = str(acc.get("nickname"));
                    if (ownerNick.isEmpty()) ownerNick = "Conta " + uid;
                    title = it.path("title").asText(null);
                    break;
                }
            }
            if (ownerUid != null) break;
        }
        if (ownerUid == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Item não encontrado no snapshot. Rode o refresh.");
        }

        DeleteResult res = svc.deleteListing(ownerUid, itemId, close_only);
        logDelete(itemId, ownerUid, ownerNick, title, sku, batch_id, res, close_only);

        if (!res.ok()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "ML recusou (" + res.action() + " status " + res.status() + "): " + res.detail());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("item_id", itemId);
        out.put("user_id", ownerUid);
        out.put("ok", res.ok());
        out.put("action", res.action());
        out.put("status", res.status());
        out.put("detail", res.detail());
        return out;
    }

    @GetMapping("/items/{itemId}")
    public Map<String, Object> itemDetail(@PathVariable String itemId,
                                          @RequestParam(defaultValue = "90") int days) {
        List<Map<String, Object>> accounts = accountsFor("all");
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        Map<String, Object> meta = null;
        for (Map<String, Object> acc : accounts) {
            long uid = asLong(acc.get("user_id"));
            JsonNode inv = svc.loadInventory(uid);
            if (inv == null) continue;
            for (JsonNode it : inv.path("items")) {
                if (itemId.equals(it.path("id").asText(null))) {
                    JsonNode sales = svc.loadSales(uid);
                    JsonNode sale = sales == null ? null : sales.path("by_item").get(itemId);
                    meta = M.convertValue(it, MAP);
                    meta.put("user_id", uid);
                    meta.put("nickname", acc.get("nickname"));
                    meta.put("sale", sale == null ? Map.of() : M.convertValue(sale, MAP));
                    break;
                }
            }
            if (meta != null) break;
        }

        String dateCreated = meta == null ? null : str(meta.get("date_created"));
        Map<String, Object> visits = svc.itemVisitsSeries(itemId, days, true);
        Map<String, Object> questions = svc.itemQuestions(itemId);
        int visitsLifetime = svc.itemVisitsSince(itemId, dateCreated);
        Map<String, Object> ads = meta != null ? svc.getItemAds(itemId, asLong(meta.get("user_id")), days) : null;

        Map<String, Object> sale = meta == null ? Map.of() : asMap(meta.get("sale"));
        int qtySold = asInt(sale.get("qty"));
        double revenue = round2(asDbl(sale.get("revenue")));
        int totalVisits = asInt(visits.get("total"));
        int soldLifetime = meta == null ? 0 : asInt(meta.get("sold_quantity"));

        List<String> dates = dateRange(today.minusDays(days - 1), today);
        Map<String, Object> daily = asMap(sale.get("daily"));
        List<Integer> salesSeries = new ArrayList<>();
        List<Double> revenueSeries = new ArrayList<>();
        for (String d : dates) {
            Map<String, Object> day = asMap(daily.get(d));
            salesSeries.add(asInt(day.get("qty")));
            revenueSeries.add(round2(asDbl(day.get("revenue"))));
        }

        List<Integer> adsSeries = null;
        if (meta != null && ads != null && asInt(ads.get("units")) > 0) {
            adsSeries = svc.getItemAdsSeries(itemId, asLong(meta.get("user_id")), dates);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("item_id", itemId);
        out.put("meta", meta);
        out.put("visits", visits);
        out.put("visits_lifetime", visitsLifetime);
        out.put("sold_lifetime", soldLifetime);
        out.put("questions", questions);
        out.put("qty_sold", qtySold);
        out.put("revenue", revenue);
        out.put("last_sale_date", sale.get("last_sale_date"));
        Map<String, Object> salesBlock = new LinkedHashMap<>();
        salesBlock.put("dates", dates);
        salesBlock.put("series", salesSeries);
        salesBlock.put("revenue_series", revenueSeries);
        out.put("sales", salesBlock);
        out.put("ads_series", adsSeries);
        out.put("conversion", visitsLifetime > 0 ? round4((double) soldLifetime / visitsLifetime) : null);
        out.put("questions_per_visit", totalVisits > 0 ? round4((double) asInt(questions.get("total")) / totalVisits) : null);
        out.put("ads", ads);
        return out;
    }

    @GetMapping("/sku/{sku}")
    public Map<String, Object> skuDetail(@PathVariable String sku,
                                         @RequestParam(defaultValue = "90") int days,
                                         @RequestParam(defaultValue = "false") boolean lite) {
        List<Map<String, Object>> accounts = accountsFor("all");
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        List<Map<String, Object>> members = new ArrayList<>();
        Map<String, Map<String, Object>> dailyById = new HashMap<>();
        for (Map<String, Object> acc : accounts) {
            long uid = asLong(acc.get("user_id"));
            JsonNode inv = svc.loadInventory(uid);
            if (inv == null) continue;
            JsonNode sales = svc.loadSales(uid);
            JsonNode byItem = sales == null ? null : sales.path("by_item");
            for (JsonNode it : inv.path("items")) {
                if (sku.equals(it.path("sku").asText(""))) {
                    String id = it.path("id").asText();
                    JsonNode sale = byItem == null ? null : byItem.get(id);
                    Map<String, Object> saleMap = sale == null ? Map.of() : M.convertValue(sale, MAP);
                    dailyById.put(id, asMap(saleMap.get("daily")));
                    Map<String, Object> m = M.convertValue(it, MAP);
                    m.put("user_id", uid);
                    m.put("nickname", acc.get("nickname"));
                    m.put("qty_sold", asInt(saleMap.get("qty")));
                    m.put("revenue", round2(asDbl(saleMap.get("revenue"))));
                    m.put("last_sale_date", saleMap.get("last_sale_date"));
                    m.put("days_since_last_sale", daysSince(str(saleMap.get("last_sale_date")), today));
                    members.add(m);
                }
            }
        }

        if (members.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SKU não encontrado no snapshot. Rode o refresh.");
        }

        final boolean withSeries = !lite;
        List<Map<String, Object>> visitsEach = client.parallelMap(members,
                m -> svc.itemVisitsSeries(str(m.get("id")), days, withSeries));
        List<Integer> lifetimeEach = client.parallelMap(members,
                m -> svc.itemVisitsSince(str(m.get("id")), str(m.get("date_created"))));
        List<Map<String, Object>> adsEach = lite
                ? nullList(members.size())
                : client.parallelMap(members, m -> svc.getItemAds(str(m.get("id")), asLong(m.get("user_id")), days));

        for (int i = 0; i < members.size(); i++) {
            Map<String, Object> m = members.get(i);
            int v = asInt(visitsEach.get(i).get("total"));
            int life = lifetimeEach.get(i) == null ? 0 : lifetimeEach.get(i);
            m.put("visits", v);
            m.put("visits_lifetime", life);
            int soldLifetime = asInt(m.get("sold_quantity"));
            m.put("sold_lifetime", soldLifetime);
            m.put("conversion", life > 0 ? round4((double) soldLifetime / life) : null);
            m.put("ads", adsEach.get(i));
        }

        int totalSold = members.stream().mapToInt(m -> asInt(m.get("qty_sold"))).sum();
        int totalSoldLifetime = members.stream().mapToInt(m -> asInt(m.get("sold_lifetime"))).sum();
        double totalRevenue = round2(members.stream().mapToDouble(m -> asDbl(m.get("revenue"))).sum());
        int totalVisits = members.stream().mapToInt(m -> asInt(m.get("visits"))).sum();
        int totalVisitsLifetime = members.stream().mapToInt(m -> asInt(m.get("visits_lifetime"))).sum();
        int totalStock = members.stream().mapToInt(m -> asInt(m.get("available_quantity"))).sum();
        double adsCost = round2(adsEach.stream().mapToDouble(a -> a == null ? 0 : asDbl(a.get("cost"))).sum());
        int adsUnits = adsEach.stream().mapToInt(a -> a == null ? 0 : asInt(a.get("units"))).sum();
        double adsAmount = round2(adsEach.stream().mapToDouble(a -> a == null ? 0 : asDbl(a.get("amount"))).sum());
        int adsClicks = adsEach.stream().mapToInt(a -> a == null ? 0 : asInt(a.get("clicks"))).sum();

        List<String> chartDates = null;
        if (!lite) {
            chartDates = dateRange(today.minusDays(days - 1), today);
            for (Map<String, Object> m : members) {
                Map<String, Object> daily = dailyById.getOrDefault(str(m.get("id")), Map.of());
                List<Integer> series = new ArrayList<>();
                for (String d : chartDates) series.add(asInt(asMap(daily.get(d)).get("qty")));
                m.put("sales_series", series);
                m.put("ads_series", null);
            }
            List<Map<String, Object>> membersAds = new ArrayList<>();
            for (int i = 0; i < members.size(); i++) {
                Map<String, Object> a = adsEach.get(i);
                if (a != null && asInt(a.get("units")) > 0) membersAds.add(members.get(i));
            }
            final List<String> cd = chartDates;
            List<List<Integer>> perMember = client.parallelMap(membersAds,
                    m -> svc.getItemAdsSeries(str(m.get("id")), asLong(m.get("user_id")), cd));
            for (int i = 0; i < membersAds.size(); i++) membersAds.get(i).put("ads_series", perMember.get(i));
        }

        Map<String, Object> best = members.stream()
                .max(Comparator.comparingInt((Map<String, Object> m) -> asInt(m.get("qty_sold")))
                        .thenComparingDouble(m -> m.get("conversion") == null ? 0 : asDbl(m.get("conversion"))))
                .orElse(null);

        members.sort(Comparator.comparingInt((Map<String, Object> m) -> asInt(m.get("qty_sold"))).reversed());

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("total_sold", totalSold);
        totals.put("total_sold_lifetime", totalSoldLifetime);
        totals.put("total_revenue", totalRevenue);
        totals.put("total_visits", totalVisits);
        totals.put("total_visits_lifetime", totalVisitsLifetime);
        totals.put("total_stock", totalStock);
        totals.put("conversion", totalVisitsLifetime > 0 ? round4((double) totalSoldLifetime / totalVisitsLifetime) : null);
        totals.put("item_count", members.size());
        totals.put("ads_cost", adsCost);
        totals.put("ads_units", adsUnits);
        totals.put("ads_amount", adsAmount);
        totals.put("ads_clicks", adsClicks);
        totals.put("ads_acos", adsAmount > 0 ? round2(adsCost / adsAmount * 100) : null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sku", sku);
        out.put("members", members);
        out.put("best_item_id", best == null ? null : best.get("id"));
        out.put("by_modality", byModality(members));
        out.put("dates", chartDates);
        out.put("totals", totals);
        return out;
    }

    // ---------- helpers de agregação (ex-funções de módulo do Python) ----------

    private record BuildResult(List<Map<String, Object>> rows, List<Map<String, Object>> missing) {}

    private List<Map<String, Object>> accountsFor(String account) {
        List<Map<String, Object>> accounts = auth.listAccounts();
        if (account != null && !"all".equals(account)) {
            long uid;
            try {
                uid = Long.parseLong(account);
            } catch (NumberFormatException e) {
                return List.of();
            }
            return filter(accounts, a -> asLong(a.get("user_id")) == uid);
        }
        return accounts;
    }

    private BuildResult buildRows(List<Map<String, Object>> accounts, LocalDate today) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> missing = new ArrayList<>();
        for (Map<String, Object> acc : accounts) {
            long uid = asLong(acc.get("user_id"));
            JsonNode inv = svc.loadInventory(uid);
            if (inv == null) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("user_id", uid);
                m.put("nickname", acc.get("nickname"));
                missing.add(m);
                continue;
            }
            JsonNode sales = svc.loadSales(uid);
            JsonNode byItem = sales == null ? null : sales.path("by_item");
            JsonNode visits = svc.loadVisits(uid);
            JsonNode visitsByItem = visits == null ? null : visits.path("by_item");
            for (JsonNode it : inv.path("items")) {
                String id = it.path("id").asText();
                JsonNode sale = byItem == null ? null : byItem.get(id);
                int qty = sale == null ? 0 : sale.path("qty").asInt(0);
                String last = sale == null ? null : (sale.hasNonNull("last_sale_date") ? sale.get("last_sale_date").asText() : null);
                int soldLifetime = it.path("sold_quantity").asInt(0);
                JsonNode vl = visitsByItem == null ? null : visitsByItem.get(id);
                Map<String, Object> row = M.convertValue(it, MAP);
                row.put("user_id", uid);
                row.put("nickname", acc.get("nickname"));
                row.put("qty_sold", qty);
                row.put("sold_lifetime", soldLifetime);
                row.put("visits_lifetime", (vl == null || vl.isNull()) ? null : vl.asInt());
                row.put("revenue", sale == null ? 0.0 : round2(sale.path("revenue").asDouble(0)));
                row.put("last_sale_date", last);
                row.put("days_since_last_sale", daysSince(last, today));
                row.put("never_sold", soldLifetime == 0);
                rows.add(row);
            }
        }
        return new BuildResult(rows, missing);
    }

    private static String modality(String logisticType) {
        String lt = logisticType == null ? "" : logisticType.toLowerCase();
        if ("fulfillment".equals(lt)) return "full";
        if ("self_service".equals(lt)) return "flex";
        return "padrao";
    }

    private static Map<String, Object> byModality(List<Map<String, Object>> rows) {
        Map<String, long[]> qtyCount = new LinkedHashMap<>();   // [qty, count]
        Map<String, double[]> rev = new LinkedHashMap<>();
        for (String k : List.of("full", "flex", "padrao")) { qtyCount.put(k, new long[2]); rev.put(k, new double[1]); }
        for (Map<String, Object> r : rows) {
            String b = modality(str(r.get("logistic_type")));
            qtyCount.get(b)[0] += asInt(r.get("qty_sold"));
            qtyCount.get(b)[1] += 1;
            rev.get(b)[0] += asDbl(r.get("revenue"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : List.of("full", "flex", "padrao")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("qty", qtyCount.get(k)[0]);
            m.put("revenue", round2(rev.get(k)[0]));
            m.put("count", qtyCount.get(k)[1]);
            out.put(k, m);
        }
        return out;
    }

    private Map<String, Object> adsTotalsFor(List<Map<String, Object>> accounts) {
        long units = 0, clicks = 0, prints = 0;
        double amount = 0, cost = 0;
        for (Map<String, Object> acc : accounts) {
            JsonNode ads = svc.loadAds(asLong(acc.get("user_id")));
            JsonNode t = ads == null ? null : ads.path("totals");
            if (t == null) continue;
            units += t.path("units").asInt(0);
            amount += t.path("amount").asDouble(0);
            cost += t.path("cost").asDouble(0);
            clicks += t.path("clicks").asInt(0);
            prints += t.path("prints").asInt(0);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("units", units);
        out.put("amount", round2(amount));
        out.put("cost", round2(cost));
        out.put("clicks", clicks);
        out.put("prints", prints);
        return out;
    }

    private static Map<String, Object> byAds(Map<String, Object> adsTotals, long totalSoldUnits) {
        long adUnits = asLong(adsTotals.get("units"));
        double adAmount = round2(asDbl(adsTotals.get("amount")));
        double adCost = round2(asDbl(adsTotals.get("cost")));
        long adClicks = asLong(adsTotals.get("clicks"));
        long adPrints = asLong(adsTotals.get("prints"));
        long organicUnits = Math.max(0, totalSoldUnits - adUnits);
        Map<String, Object> ads = new LinkedHashMap<>();
        ads.put("units", adUnits);
        ads.put("amount", adAmount);
        ads.put("cost", adCost);
        ads.put("acos", adAmount > 0 ? round2(adCost / adAmount * 100) : null);
        ads.put("clicks", adClicks);
        ads.put("prints", adPrints);
        ads.put("cpc", adClicks > 0 ? round2(adCost / adClicks) : null);
        ads.put("ctr", adPrints > 0 ? round2((double) adClicks / adPrints * 100) : null);
        ads.put("conversion", adClicks > 0 ? round2((double) adUnits / adClicks * 100) : null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ads", ads);
        out.put("organic", Map.of("units", organicUnits));
        return out;
    }

    private static Integer daysSince(String lastSaleDate, LocalDate today) {
        if (lastSaleDate == null || lastSaleDate.length() < 10) return null;
        try {
            LocalDate d = LocalDate.parse(lastSaleDate.substring(0, 10));
            return (int) (today.toEpochDay() - d.toEpochDay());
        } catch (Exception e) {
            return null;
        }
    }

    private Comparator<Map<String, Object>> comparatorFor(String field) {
        return switch (field) {
            case "visits" -> Comparator.comparingLong(r -> asInt(r.get("visits_lifetime")));
            case "revenue" -> Comparator.comparingDouble(r -> asDbl(r.get("revenue")));
            case "price" -> Comparator.comparingDouble(r -> asDbl(r.get("price")));
            case "last_sale" -> Comparator.comparingLong(r -> r.get("days_since_last_sale") == null ? BIG : asInt(r.get("days_since_last_sale")));
            case "created" -> Comparator.comparing(r -> str(r.get("date_created")));
            default -> Comparator.comparingLong(r -> asInt(r.get("sold_lifetime"))); // sales
        };
    }

    private static String[] parseSort(String s, String defaultSort) {
        String[] parsed = splitSort(s);
        if (!List.of("sales", "visits", "revenue", "price", "last_sale", "created").contains(parsed[0])
                || !List.of("asc", "desc").contains(parsed[1])) {
            parsed = splitSort(defaultSort);
        }
        return parsed;
    }

    private static String[] splitSort(String s) {
        int idx = s.lastIndexOf('_');
        if (idx < 0) return new String[]{s, ""};
        return new String[]{s.substring(0, idx), s.substring(idx + 1)};
    }

    private void logDelete(String itemId, long ownerUid, String ownerNick, String title, String sku,
                           String batchId, DeleteResult res, boolean closeOnly) {
        try {
            ObjectNode item = M.createObjectNode();
            item.put("id", itemId);
            item.set("title", title == null ? null : M.getNodeFactory().textNode(title));
            ObjectNode group = M.createObjectNode();
            group.put("user_id", ownerUid);
            group.put("nickname", ownerNick);
            group.set("item_ids", M.createArrayNode().add(itemId));
            group.set("items", M.createArrayNode().add(item));
            ObjectNode payload = M.createObjectNode();
            payload.put("sku", sku);
            payload.put("action", res.action());
            payload.put("close_only", closeOnly);
            payload.set("groups", M.createArrayNode().add(group));

            OperationLog logRow = new OperationLog("delete_listing", List.of(itemId), payload,
                    M.valueToTree(res), res.ok() ? "success" : "error",
                    res.ok() ? null : clip(str(res.detail()), 500));
            logRow.setUserId(ownerUid);
            logRow.setBatchId(batchId);
            opLogs.save(logRow);
        } catch (Exception e) {
            // Nunca deixar a falha de log derrubar a exclusão, que já aconteceu no ML.
            log.warn("perf delete: falha ao gravar OperationLog de {}: {}", itemId, e.getMessage());
        }
    }

    // ---------- utilitários ----------

    private static <T> List<T> filter(List<T> in, java.util.function.Predicate<T> p) {
        List<T> out = new ArrayList<>();
        for (T t : in) if (p.test(t)) out.add(t);
        return out;
    }

    private static List<String> dateRange(LocalDate from, LocalDate to) {
        List<String> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) out.add(d.toString());
        return out;
    }

    private static Map<String, Object> paging(int total, int offset, int limit) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("total", total);
        p.put("offset", offset);
        p.put("limit", limit);
        return p;
    }

    private static <T> List<T> nullList(int n) {
        List<T> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(null);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }
    private static int asInt(Object o) { return o instanceof Number n ? n.intValue() : 0; }
    private static long asLong(Object o) { return o instanceof Number n ? n.longValue() : 0L; }
    private static double asDbl(Object o) { return o instanceof Number n ? n.doubleValue() : 0.0; }
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }
    private static String clip(String s, int n) { return s == null ? null : (s.length() > n ? s.substring(0, n) : s); }
}
