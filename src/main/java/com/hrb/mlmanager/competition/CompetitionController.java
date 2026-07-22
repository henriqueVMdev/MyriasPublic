package com.hrb.mlmanager.competition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hrb.mlmanager.auth.PanelSecurity;
import com.hrb.mlmanager.meli.MeliAuthService;
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

/** Análise de concorrência: snapshot por conta + detalhe ao vivo por item. */
@RestController
@RequestMapping("/api/competition")
public class CompetitionController {

    private final MeliCompetitionService svc;
    private final CompetitionJobs jobs;
    private final MeliAuthService auth;
    private final PanelSecurity security;

    public CompetitionController(MeliCompetitionService svc, CompetitionJobs jobs,
                                 MeliAuthService auth, PanelSecurity security) {
        this.svc = svc;
        this.jobs = jobs;
        this.auth = auth;
        this.security = security;
    }

    @GetMapping("")
    public Map<String, Object> report(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest request) {
        security.require(request, "concorrencia");
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
            out.put("summary", MeliCompetitionService.summarize(MeliCompetitionService.M.createArrayNode()));
            out.put("items", List.of());
            out.put("paging", Map.of("total", 0, "offset", safeOffset, "limit", safeLimit));
            out.put("warnings", List.of());
            return out;
        }

        List<JsonNode> rows = filterRows(snapshot.path("items"), q, status);
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
        out.put("summary", snapshot.has("summary") ? snapshot.get("summary")
                : MeliCompetitionService.summarize(snapshot.path("items")));
        out.put("items", rows.subList(from, to));
        out.put("paging", Map.of("total", rows.size(), "offset", safeOffset, "limit", safeLimit));
        out.put("warnings", snapshot.has("warnings") ? snapshot.get("warnings")
                : MeliCompetitionService.M.createArrayNode());
        return out;
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(HttpServletRequest request) {
        security.require(request, "concorrencia");
        long userId = auth.requireActiveAccountId();
        if (jobs.isRunning(userId)) {
            return Map.of("started", false, "already_running", true, "user_id", userId);
        }
        Map<String, Object> account = auth.listAccounts().stream()
                .filter(acc -> number(acc.get("user_id")) == userId)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conta ativa nao encontrada."));
        boolean started = jobs.enqueueBuild(userId, String.valueOf(account.getOrDefault("nickname", "")));
        return Map.of("started", started, "already_running", !started, "user_id", userId);
    }

    @GetMapping("/items/{itemId}")
    public ObjectNode analyzeItem(@PathVariable String itemId, HttpServletRequest request) {
        security.require(request, "concorrencia");
        long userId = auth.requireActiveAccountId();
        return svc.analyzeItem(userId, itemId);
    }

    // ---------- filtro/ordenação ----------

    private static List<JsonNode> filterRows(JsonNode items, String q, String status) {
        List<JsonNode> rows = new ArrayList<>();
        items.forEach(rows::add);
        if (q != null && !q.isBlank()) {
            String needle = q.strip().toLowerCase();
            rows.removeIf(it -> !(it.path("title").asText("").toLowerCase().contains(needle)
                    || it.path("sku").asText("").toLowerCase().contains(needle)
                    || it.path("id").asText("").toLowerCase().contains(needle)));
        }
        if (status != null && !status.isBlank()) {
            rows.removeIf(it -> !status.equals(it.path("comp_status").asText(null)));
        }
        // Maior perda primeiro: quem tem maior gap de preço (perdendo) no topo.
        rows.sort(Comparator.<JsonNode>comparingDouble(it ->
                it.path("price_gap").isNumber() ? -it.path("price_gap").asDouble() : 1).thenComparing(
                it -> it.path("comp_status").asText("")));
        return rows;
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
