package com.myrias.mlmanager.ops;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leitura/agregação do histórico de operações. Espelho de backend/app/api/logs.py.
 *
 * Como {@code item_ids} é JSON em coluna texto (e não um ARRAY do Postgres), o
 * agrupamento por {@code batch_id} (/operations) e por item (/by-item) — que no
 * Python era SQL com {@code unnest}/{@code array_agg}/{@code AT TIME ZONE} — aqui
 * roda em memória, portável entre H2 e Postgres. O volume de logs deste app
 * (revenda de autopeças) é modesto, então carregar o conjunto filtrado e agregar
 * em Java é aceitável; se crescer muito, migrar para SQL nativo por dialeto.
 *
 * Timestamps: {@code created_at} é {@link Instant} (UTC, sem ambiguidade); o
 * {@code toString()} já sai em ISO-8601 com sufixo Z, que o {@code new Date(...)}
 * do frontend converte pro fuso local — equivale ao {@code _iso_utc} do Python.
 */
@Service
public class OperationLogService {

    /** Tipos de atendimento (respostas) — têm seção própria, fora das operações. */
    static final List<String> ATENDIMENTO_TYPES = List.of("answer_question", "send_message");

    /** Filtro de data do /operations compara no fuso local, como o Python. */
    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    private final OperationLogRepository repo;

    public OperationLogService(OperationLogRepository repo) {
        this.repo = repo;
    }

    // ---- /logs (lista simples com filtros) ----------------------------------

    @Transactional(readOnly = true)
    public Map<String, Object> listLogs(String operationType, String status, String itemId,
                                        int offset, int limit) {
        Specification<OperationLog> spec = byColumns(operationType, status, null, null);

        // item_id filtra dentro do JSON de item_ids — feito em memória (a coluna é
        // texto convertido, não dá pra LIKE portável no Criteria). O recorte por
        // item costuma ser pequeno (histórico de um anúncio).
        if (itemId != null) {
            List<OperationLog> rows = repo.findAll(spec, Sort.by(Sort.Direction.DESC, "createdAt"));
            List<OperationLog> filtered = new ArrayList<>();
            for (OperationLog r : rows) {
                if (r.getItemIds() != null && r.getItemIds().contains(itemId)) filtered.add(r);
            }
            int total = filtered.size();
            List<OperationLog> page = filtered.subList(Math.min(offset, total), Math.min(offset + limit, total));
            return Map.of("logs", page.stream().map(this::serializeListRow).toList(),
                    "paging", paging(total, offset, limit));
        }

        long total = repo.count(spec);
        List<OperationLog> rows = repo.findAll(spec,
                PageRequest.of(pageOf(offset, limit), limit, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
        return Map.of("logs", rows.stream().map(this::serializeListRow).toList(),
                "paging", paging((int) total, offset, limit));
    }

    // ---- /actors ------------------------------------------------------------

    @Transactional(readOnly = true)
    public Map<String, Object> listActors() {
        return Map.of("actors", repo.findDistinctActors());
    }

    // ---- /operations (agrupado por batch_id) --------------------------------

    @Transactional(readOnly = true)
    public Map<String, Object> listOperations(String operationType, String status, String actor,
                                              String dateFrom, String dateTo, int offset, int limit) {
        // Exclui os tipos de atendimento; filtra operation_type/actor no banco. O
        // `status` NÃO é filtro de coluna aqui: ele é HAVING sobre o grupo agregado
        // (statusMatches mais abaixo), não sobre a linha individual.
        Specification<OperationLog> spec = byColumns(operationType, null, actor, ATENDIMENTO_TYPES);
        List<OperationLog> rows = repo.findAll(spec);

        LocalDateTime from = parseLocal(dateFrom, false);
        LocalDateTime to = parseLocal(dateTo, true);

        // Agrupa por batch_id (ou 'log-<id>' para linha única), aplicando o filtro
        // de data no fuso local de São Paulo — espelha o AT TIME ZONE do Python.
        Map<String, List<OperationLog>> groups = new LinkedHashMap<>();
        for (OperationLog r : rows) {
            if ((from != null || to != null)) {
                Instant created = r.getCreatedAt();
                if (created == null) continue; // sem data não dá pra comparar (NULL no SQL também sairia)
                LocalDateTime local = created.atZone(SAO_PAULO).toLocalDateTime();
                if (from != null && local.isBefore(from)) continue;
                if (to != null && local.isAfter(to)) continue;
            }
            String key = r.getBatchId() != null ? r.getBatchId() : "log-" + r.getId();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }

        List<GroupAgg> built = new ArrayList<>();
        for (Map.Entry<String, List<OperationLog>> e : groups.entrySet()) {
            GroupAgg g = aggregate(e.getKey(), e.getValue());
            if (statusMatches(status, g.success(), g.error(), g.partial())) built.add(g);
        }
        built.sort(Comparator.comparing(GroupAgg::lastAt,
                Comparator.nullsLast(Comparator.reverseOrder())));

        int total = built.size();
        List<GroupAgg> page = built.subList(Math.min(offset, total), Math.min(offset + limit, total));

        List<Map<String, Object>> operations = new ArrayList<>();
        for (GroupAgg g : page) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", g.key());
            m.put("operation_type", g.opType());
            m.put("actor", g.actor());
            m.put("status", g.groupStatus());
            m.put("total", g.total());
            m.put("success", g.success());
            m.put("error", g.error());
            m.put("partial", g.partial());
            m.put("created_at", iso(g.lastAt()));
            // Linhas-filho da operação (sem o response, que é pesado e só vai no detalhe),
            // ordenadas por created_at asc.
            List<OperationLog> children = new ArrayList<>(g.rows());
            children.sort(Comparator.comparing(OperationLog::getCreatedAt,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            m.put("children", children.stream().map(r -> serialize(r, false)).toList());
            operations.add(m);
        }

        return Map.of("operations", operations, "paging", paging(total, offset, limit));
    }

    // ---- /operations/{key} (detalhe completo) -------------------------------

    @Transactional(readOnly = true)
    public Map<String, Object> operationDetail(String key) {
        List<OperationLog> rows;
        if (key.startsWith("log-")) {
            long id;
            try {
                id = Long.parseLong(key.substring(4));
            } catch (NumberFormatException e) {
                id = -1L;
            }
            rows = repo.findById(id).map(List::of).orElse(List.of());
        } else {
            rows = repo.findByBatchIdOrderByCreatedAtAsc(key);
        }
        return Map.of("key", key, "rows", rows.stream().map(r -> serialize(r, true)).toList());
    }

    // ---- /atendimento -------------------------------------------------------

    @Transactional(readOnly = true)
    public Map<String, Object> listAtendimento(String operationType, String actor, int offset, int limit) {
        Specification<OperationLog> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(root.get("operationType").in(ATENDIMENTO_TYPES));
            if (operationType != null) ps.add(cb.equal(root.get("operationType"), operationType));
            if (actor != null) ps.add(cb.equal(root.get("actor"), actor));
            return cb.and(ps.toArray(new Predicate[0]));
        };
        long total = repo.count(spec);
        List<OperationLog> rows = repo.findAll(spec,
                PageRequest.of(pageOf(offset, limit), limit, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
        return Map.of("logs", rows.stream().map(r -> serialize(r, true)).toList(),
                "paging", paging((int) total, offset, limit));
    }

    // ---- /by-item -----------------------------------------------------------

    @Transactional(readOnly = true)
    public Map<String, Object> logsByItem(int offset, int limit) {
        // Explode os logs por item_id em memória (equivale ao unnest do Postgres).
        Map<String, ItemAgg> map = new LinkedHashMap<>();
        for (OperationLog r : repo.findAll()) {
            List<String> ids = r.getItemIds();
            if (ids == null || ids.isEmpty()) continue;
            for (String itemId : new LinkedHashSet<>(ids)) { // distinct por linha, como o array_agg sensato
                ItemAgg a = map.computeIfAbsent(itemId, k -> new ItemAgg());
                a.opCount++;
                switch (r.getStatus() == null ? "" : r.getStatus()) {
                    case "error" -> a.errorCount++;
                    case "success" -> a.successCount++;
                    case "partial" -> a.partialCount++;
                    default -> { /* status desconhecido não entra nas contagens */ }
                }
                if (r.getOperationType() != null) a.opTypes.add(r.getOperationType());
                if (r.getCreatedAt() != null && (a.lastAt == null || r.getCreatedAt().isAfter(a.lastAt))) {
                    a.lastAt = r.getCreatedAt();
                }
            }
        }

        List<Map.Entry<String, ItemAgg>> entries = new ArrayList<>(map.entrySet());
        entries.sort(Comparator.comparing((Map.Entry<String, ItemAgg> e) -> e.getValue().lastAt,
                Comparator.nullsLast(Comparator.reverseOrder())));

        int total = entries.size();
        List<Map.Entry<String, ItemAgg>> page = entries.subList(Math.min(offset, total), Math.min(offset + limit, total));

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map.Entry<String, ItemAgg> e : page) {
            ItemAgg a = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("item_id", e.getKey());
            m.put("op_count", a.opCount);
            m.put("last_at", iso(a.lastAt));
            m.put("error_count", a.errorCount);
            m.put("success_count", a.successCount);
            m.put("partial_count", a.partialCount);
            m.put("op_types", new ArrayList<>(a.opTypes));
            items.add(m);
        }
        return Map.of("items", items, "paging", paging(total, offset, limit));
    }

    // ---- /stats -------------------------------------------------------------

    @Transactional(readOnly = true)
    public Map<String, Object> stats() {
        Map<String, Long> byType = new LinkedHashMap<>();
        for (Object[] row : repo.countByOperationType()) byType.put((String) row[0], (Long) row[1]);
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Object[] row : repo.countByStatus()) byStatus.put((String) row[0], (Long) row[1]);

        List<Map<String, Object>> recent = new ArrayList<>();
        for (OperationLog log : repo.findTop10ByOrderByCreatedAtDesc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", log.getId());
            m.put("operation_type", log.getOperationType());
            m.put("item_ids", log.getItemIds());
            m.put("status", log.getStatus());
            m.put("created_at", iso(log.getCreatedAt()));
            m.put("payload", log.getPayload());
            recent.add(m);
        }

        long total = byType.values().stream().mapToLong(Long::longValue).sum();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("by_type", byType);
        out.put("by_status", byStatus);
        out.put("recent", recent);
        return out;
    }

    // ---- Internos -----------------------------------------------------------

    /** Spec por colunas reais; {@code excludeTypes} remove tipos (ex.: atendimento). */
    private Specification<OperationLog> byColumns(String operationType, String status, String actor,
                                                 List<String> excludeTypes) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (operationType != null) ps.add(cb.equal(root.get("operationType"), operationType));
            if (status != null) ps.add(cb.equal(root.get("status"), status));
            if (actor != null) ps.add(cb.equal(root.get("actor"), actor));
            if (excludeTypes != null && !excludeTypes.isEmpty()) {
                ps.add(cb.not(root.get("operationType").in(excludeTypes)));
            }
            return ps.isEmpty() ? cb.conjunction() : cb.and(ps.toArray(new Predicate[0]));
        };
    }

    private GroupAgg aggregate(String key, List<OperationLog> rows) {
        int success = 0, error = 0, partial = 0;
        String opType = null, actor = null;
        Instant lastAt = null;
        for (OperationLog r : rows) {
            switch (r.getStatus() == null ? "" : r.getStatus()) {
                case "success" -> success++;
                case "error" -> error++;
                case "partial" -> partial++;
                default -> { /* ignora status desconhecido nas contagens */ }
            }
            // MIN(operation_type), MAX(actor), MAX(created_at) — igual ao SQL do Python.
            if (r.getOperationType() != null && (opType == null || r.getOperationType().compareTo(opType) < 0)) {
                opType = r.getOperationType();
            }
            if (r.getActor() != null && (actor == null || r.getActor().compareTo(actor) > 0)) {
                actor = r.getActor();
            }
            if (r.getCreatedAt() != null && (lastAt == null || r.getCreatedAt().isAfter(lastAt))) {
                lastAt = r.getCreatedAt();
            }
        }
        return new GroupAgg(key, opType, actor, groupStatus(success, error, partial),
                rows.size(), success, error, partial, lastAt, rows);
    }

    /** Status agregado de uma operação (espelho de _group_status do Python). */
    private static String groupStatus(int success, int error, int partial) {
        if (partial > 0 || (error > 0 && success > 0)) return "partial";
        if (error > 0) return "error";
        return "success";
    }

    /** Filtro HAVING por status agregado (espelha os HAVINGs do SQL do Python). */
    private static boolean statusMatches(String status, int success, int error, int partial) {
        if (status == null) return true;
        return switch (status) {
            case "success" -> error == 0 && partial == 0;
            case "error" -> success == 0 && partial == 0 && error > 0;
            case "partial" -> partial > 0 || (error > 0 && success > 0);
            default -> true; // status fora do conjunto conhecido → sem HAVING, como o Python
        };
    }

    private Map<String, Object> serialize(OperationLog log, boolean withResponse) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", log.getId());
        m.put("operation_type", log.getOperationType());
        m.put("item_ids", log.getItemIds());
        m.put("payload", log.getPayload());
        m.put("status", log.getStatus());
        m.put("error_message", log.getErrorMessage());
        m.put("batch_id", log.getBatchId());
        m.put("actor", log.getActor());
        m.put("user_id", log.getUserId());
        m.put("created_at", iso(log.getCreatedAt()));
        m.put("failed_ids", failedItemIds(log.getResponse()));
        if (withResponse) m.put("response", log.getResponse());
        return m;
    }

    /**
     * item_ids que falharam numa response, cobrindo os 3 formatos: promoção
     * ({@code results[{item_id, ok}]}), bulk conta única ({@code errors[{item_id}]})
     * e bulk multi-conta ({@code per_account[].errors[{item_id}]}). Sempre presente
     * (mesmo sem a response completa) pra listagem marcar só o anúncio que falhou.
     */
    private static List<String> failedItemIds(JsonNode response) {
        if (response == null || !response.isObject()) return List.of();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (JsonNode r : response.path("results")) {
            String id = r.path("item_id").asText(null);
            if (id != null && !id.isBlank() && !r.path("ok").asBoolean(false)) ids.add(id);
        }
        for (JsonNode e : response.path("errors")) {
            String id = e.path("item_id").asText(null);
            if (id != null && !id.isBlank()) ids.add(id);
        }
        for (JsonNode acc : response.path("per_account")) {
            for (JsonNode e : acc.path("errors")) {
                String id = e.path("item_id").asText(null);
                if (id != null && !id.isBlank()) ids.add(id);
            }
        }
        return new ArrayList<>(ids);
    }

    /** Shape enxuto da lista /logs (com response, sem batch_id/actor/user_id). */
    private Map<String, Object> serializeListRow(OperationLog log) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", log.getId());
        m.put("operation_type", log.getOperationType());
        m.put("item_ids", log.getItemIds());
        m.put("payload", log.getPayload());
        m.put("response", log.getResponse());
        m.put("status", log.getStatus());
        m.put("error_message", log.getErrorMessage());
        m.put("created_at", iso(log.getCreatedAt()));
        m.put("failed_ids", failedItemIds(log.getResponse()));
        return m;
    }

    /** Parse leniente do filtro de data: "YYYY-MM-DD" ou "YYYY-MM-DDTHH:mm:ss". */
    private static LocalDateTime parseLocal(String s, boolean endOfDay) {
        if (s == null || s.isBlank()) return null;
        String v = s.trim();
        try {
            if (v.length() <= 10) {
                LocalDate d = LocalDate.parse(v);
                return endOfDay ? d.atTime(23, 59, 59) : d.atStartOfDay();
            }
            return LocalDateTime.parse(v);
        } catch (Exception e) {
            return null;
        }
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static int pageOf(int offset, int limit) {
        return limit > 0 ? offset / limit : 0;
    }

    private static Map<String, Object> paging(int total, int offset, int limit) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("total", total);
        p.put("offset", offset);
        p.put("limit", limit);
        return p;
    }

    /** Agregado de uma operação (grupo por batch_id) antes de virar JSON. */
    private record GroupAgg(String key, String opType, String actor, String groupStatus,
                            int total, int success, int error, int partial,
                            Instant lastAt, List<OperationLog> rows) {}

    /** Acumulador mutável por item_id no /by-item. */
    private static final class ItemAgg {
        int opCount, errorCount, successCount, partialCount;
        Instant lastAt;
        final Set<String> opTypes = new TreeSet<>();
    }
}
