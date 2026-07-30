package com.myrias.mlmanager.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myrias.mlmanager.auth.PanelSecurity;
import com.myrias.mlmanager.meli.MeliAuthService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Endpoints da página de qualidade/completude dos anúncios. Espelho de api/quality.py. */
@RestController
@RequestMapping("/api/quality")
public class QualityController {

    private final MeliQualityService svc;
    private final QualityJobs jobs;
    private final MeliAuthService auth;
    private final PanelSecurity security;

    public QualityController(MeliQualityService svc, QualityJobs jobs,
                             MeliAuthService auth, PanelSecurity security) {
        this.svc = svc;
        this.jobs = jobs;
        this.auth = auth;
        this.security = security;
    }

    @GetMapping("")
    public Map<String, Object> qualityReport(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String issue,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest request) {
        security.require(request, "quality");
        long userId = auth.requireActiveAccountId();
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, Math.min(limit, 100));

        ObjectNode snapshot = svc.load(userId);
        if (snapshot == null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("has_snapshot", false);
            out.put("refreshing", jobs.isRunning(userId));
            out.put("status", "empty");
            out.put("processed", 0);
            out.put("total", 0);
            out.put("validating_count", 0);
            out.put("summary", MeliQualityService.summarize(MeliQualityService.M.createArrayNode()));
            out.put("items", List.of());
            out.put("paging", Map.of("total", 0, "offset", safeOffset, "limit", safeLimit));
            out.put("warnings", List.of());
            return out;
        }

        List<JsonNode> rows = filterItems(snapshot.path("items"), q, issue, status);
        String storedStatus = snapshot.path("status").asText("complete");
        if ("running".equals(storedStatus) && !jobs.isRunning(userId)) storedStatus = "interrupted";

        int from = Math.min(safeOffset, rows.size());
        int to = Math.min(from + safeLimit, rows.size());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("has_snapshot", true);
        out.put("refreshing", jobs.isRunning(userId));
        out.put("status", storedStatus);
        out.put("started_at", text(snapshot, "started_at"));
        out.put("scanned_at", text(snapshot, "scanned_at"));
        out.put("processed", snapshot.path("processed").asInt(0));
        out.put("total", snapshot.path("total").asInt(0));
        out.put("validating_count", snapshot.path("validating_item_ids").size());
        out.put("summary", snapshot.has("summary") ? snapshot.get("summary")
                : MeliQualityService.summarize(snapshot.path("items")));
        out.put("items", rows.subList(from, to));
        out.put("paging", Map.of("total", rows.size(), "offset", safeOffset, "limit", safeLimit));
        out.put("warnings", snapshot.has("warnings") ? snapshot.get("warnings")
                : MeliQualityService.M.createArrayNode());
        return out;
    }

    @PostMapping("/refresh")
    public Map<String, Object> refreshQuality(HttpServletRequest request) {
        security.require(request, "quality");
        long userId = auth.requireActiveAccountId();
        if (jobs.isRunning(userId)) {
            return Map.of("started", false, "already_running", true, "user_id", userId);
        }
        Map<String, Object> account = auth.listAccounts().stream()
                .filter(acc -> number(acc.get("user_id")) == userId)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conta ativa nao encontrada."));
        boolean started = jobs.enqueueQuality(userId, String.valueOf(account.getOrDefault("nickname", "")));
        return Map.of("started", started, "already_running", !started, "user_id", userId);
    }

    @PostMapping("/items/{itemId}/refresh")
    public Map<String, Object> refreshQualityItem(@PathVariable String itemId, HttpServletRequest request) {
        security.require(request, "quality");
        long userId = auth.requireActiveAccountId();
        int queued = jobs.enqueueItemRevalidation(userId, List.of(itemId), "manual");
        return Map.of("queued", queued > 0, "item_id", itemId, "status", "validating");
    }

    // ---------- filtro/ordenação (espelho de _filter_items) ----------

    private static List<JsonNode> filterItems(JsonNode items, String q, String issue, String status) {
        List<JsonNode> rows = new ArrayList<>();
        for (JsonNode item : items) {
            if (item.path("issues").isArray() && !item.path("issues").isEmpty()) rows.add(item);
        }
        if (q != null && !q.isBlank()) {
            String needle = q.strip().toLowerCase();
            rows.removeIf(item -> !(item.path("title").asText("").toLowerCase().contains(needle)
                    || item.path("sku").asText("").toLowerCase().contains(needle)
                    || item.path("id").asText("").toLowerCase().contains(needle)));
        }
        if (status != null && !status.isBlank()) {
            rows.removeIf(item -> !status.equals(item.path("status").asText(null)));
        }
        if (issue != null && !issue.isBlank()) {
            if (issue.startsWith("type:")) {
                String issueType = issue.substring("type:".length());
                rows.removeIf(item -> !anyIssue(item, "type", issueType));
            } else {
                rows.removeIf(item -> !anyIssue(item, "key", issue));
            }
        }
        rows.sort(Comparator
                .<JsonNode>comparingInt(item -> -item.path("issues").size())
                .thenComparing(item -> item.path("score").isMissingNode() || item.path("score").isNull())
                .thenComparingDouble(item -> item.path("score").asDouble(0)));
        return rows;
    }

    private static boolean anyIssue(JsonNode item, String field, String value) {
        for (JsonNode problem : item.path("issues")) {
            if (value.equals(problem.path(field).asText(null))) return true;
        }
        return false;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static long number(Object value) {
        if (value instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(value));
    }
}
