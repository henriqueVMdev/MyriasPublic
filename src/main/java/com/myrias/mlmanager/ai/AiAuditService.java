package com.hrb.mlmanager.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.hrb.mlmanager.auth.AppUser;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registra e consulta uso, tokens e custo real devolvido pelo OpenRouter. */
@Service
public class AiAuditService {

    private static final Logger log = LoggerFactory.getLogger(AiAuditService.class);
    private final AiCommandLogRepository repository;

    public AiAuditService(AiCommandLogRepository repository) {
        this.repository = repository;
    }

    public Tracker begin(AppUser user, String command, String selectedModel) {
        return new Tracker(this, new AiCommandLog(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                command,
                selectedModel));
    }

    void persist(AiCommandLog entry) {
        try {
            repository.save(entry);
        } catch (Exception e) {
            // Auditoria nunca deve derrubar uma resposta já obtida do agente.
            log.error("Falha salvando auditoria de IA: {}", e.getMessage(), e);
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> listCommands(Long userId, String status, String query,
                                             int offset, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        int safeOffset = Math.max(0, offset);
        Specification<AiCommandLog> spec = (root, ignored, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (userId != null) predicates.add(cb.equal(root.get("appUserId"), userId));
            if (status != null && !status.isBlank()) {
                predicates.add(cb.equal(root.get("status"), status.strip()));
            }
            if (query != null && !query.isBlank()) {
                String like = "%" + query.strip().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("command")), like),
                        cb.like(cb.lower(root.get("username")), like),
                        cb.like(cb.lower(root.get("displayName")), like),
                        cb.like(cb.lower(root.get("selectedModel")), like)));
            }
            return predicates.isEmpty()
                    ? cb.conjunction()
                    : cb.and(predicates.toArray(new Predicate[0]));
        };
        long total = repository.count(spec);
        var page = repository.findAll(spec, PageRequest.of(
                safeOffset / safeLimit,
                safeLimit,
                Sort.by(Sort.Direction.DESC, "createdAt")));
        return Map.of(
                "commands", page.getContent().stream().map(AiAuditService::serialize).toList(),
                "paging", Map.of("total", total, "offset", safeOffset, "limit", safeLimit));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> stats() {
        List<AiCommandLog> rows = repository.findAll();
        BigDecimal totalCost = BigDecimal.ZERO;
        long totalTokens = 0;
        long successes = 0;
        Map<String, UserStats> byUser = new LinkedHashMap<>();
        for (AiCommandLog row : rows) {
            totalCost = totalCost.add(row.getCost() == null ? BigDecimal.ZERO : row.getCost());
            totalTokens += row.getTotalTokens();
            if ("success".equals(row.getStatus())) successes++;
            String key = row.getAppUserId() + ":" + row.getUsername();
            UserStats user = byUser.computeIfAbsent(key,
                    ignored -> new UserStats(row.getAppUserId(), row.getUsername(), row.getDisplayName()));
            user.commands++;
            user.tokens += row.getTotalTokens();
            user.cost = user.cost.add(row.getCost() == null ? BigDecimal.ZERO : row.getCost());
        }
        List<Map<String, Object>> users = byUser.values().stream()
                .sorted((a, b) -> b.cost.compareTo(a.cost))
                .map(UserStats::serialize)
                .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total_commands", rows.size());
        out.put("successful_commands", successes);
        out.put("total_tokens", totalTokens);
        out.put("total_cost", totalCost);
        out.put("by_user", users);
        return out;
    }

    private static Map<String, Object> serialize(AiCommandLog row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.getId());
        out.put("app_user_id", row.getAppUserId());
        out.put("username", row.getUsername());
        out.put("display_name", row.getDisplayName());
        out.put("command", row.getCommand());
        out.put("reply", row.getReply());
        out.put("selected_model", row.getSelectedModel());
        out.put("actual_models", row.getActualModels());
        out.put("tool_events", row.getToolEvents());
        out.put("generation_ids", row.getGenerationIds());
        out.put("status", row.getStatus());
        out.put("error_message", row.getErrorMessage());
        out.put("prompt_tokens", row.getPromptTokens());
        out.put("completion_tokens", row.getCompletionTokens());
        out.put("total_tokens", row.getTotalTokens());
        out.put("cost", row.getCost());
        out.put("request_count", row.getRequestCount());
        out.put("duration_ms", row.getDurationMs());
        out.put("created_at", row.getCreatedAt() == null ? null : row.getCreatedAt().toString());
        return out;
    }

    public static final class Tracker {
        private final AiAuditService service;
        private final AiCommandLog entry;
        private final Instant startedAt = Instant.now();
        private final LinkedHashSet<String> actualModels = new LinkedHashSet<>();
        private final List<String> generationIds = new ArrayList<>();
        private long promptTokens;
        private long completionTokens;
        private long totalTokens;
        private BigDecimal cost = BigDecimal.ZERO;
        /** Custo só da última resposta — o teto de gasto soma iteração por iteração. */
        private BigDecimal lastCost = BigDecimal.ZERO;
        private int requestCount;
        private boolean finished;

        private Tracker(AiAuditService service, AiCommandLog entry) {
            this.service = service;
            this.entry = entry;
        }

        public void capture(JsonNode response) {
            if (response == null) return;
            requestCount++;
            String model = response.path("model").asText("");
            if (!model.isBlank()) actualModels.add(model);
            String generationId = response.path("id").asText("");
            if (!generationId.isBlank()) generationIds.add(generationId);
            JsonNode usage = response.path("usage");
            promptTokens += usage.path("prompt_tokens").asLong(0);
            completionTokens += usage.path("completion_tokens").asLong(0);
            totalTokens += usage.path("total_tokens").asLong(0);
            String rawCost = usage.path("cost").asText("0");
            try {
                lastCost = new BigDecimal(rawCost);
                cost = cost.add(lastCost);
            } catch (NumberFormatException ignored) {
                // Resposta sem custo numérico: preserva o restante da auditoria.
                lastCost = BigDecimal.ZERO;
            }
        }

        /** Custo da última resposta capturada, pro AiQuotaService debitar na hora. */
        public BigDecimal lastCost() { return lastCost; }

        public void success(String reply, List<String> toolEvents) {
            finish(reply, toolEvents, "success", null);
        }

        public void failure(String errorMessage) {
            finish(null, List.of(), "error", errorMessage);
        }

        private void finish(String reply, List<String> toolEvents, String status, String errorMessage) {
            if (finished) return;
            finished = true;
            entry.complete(reply, new ArrayList<>(actualModels), toolEvents, generationIds,
                    status, errorMessage, promptTokens, completionTokens, totalTokens, cost,
                    requestCount, Duration.between(startedAt, Instant.now()).toMillis());
            service.persist(entry);
        }
    }

    private static final class UserStats {
        final Long id;
        final String username;
        final String displayName;
        long commands;
        long tokens;
        BigDecimal cost = BigDecimal.ZERO;

        UserStats(Long id, String username, String displayName) {
            this.id = id;
            this.username = username;
            this.displayName = displayName;
        }

        Map<String, Object> serialize() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("app_user_id", id);
            out.put("username", username);
            out.put("display_name", displayName);
            out.put("commands", commands);
            out.put("tokens", tokens);
            out.put("cost", cost);
            return out;
        }
    }
}
