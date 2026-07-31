package com.myrias.mlmanager.quality;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myrias.mlmanager.meli.MeliAuthService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Coordenação das auditorias de qualidade manuais e agendadas. Espelho de
 * backend/app/services/quality_jobs.py.
 *
 * O Python usava {@code asyncio.create_task} + prioridades no rate limiter; aqui
 * usamos um pool de threads dedicado e o mesmo limiter compartilhado do
 * {@link com.myrias.mlmanager.meli.MeliClient}. ponytail: sem tiers de prioridade —
 * o semáforo do limiter serializa; reintroduzir prioridade só se a auditoria
 * atrapalhar requests interativas sob carga alta.
 */
@Component
public class QualityJobs {

    private static final Logger log = LoggerFactory.getLogger(QualityJobs.class);
    private static final long[] ITEM_RECHECK_DELAYS_MS = {5_000, 30_000, 120_000};

    private final MeliQualityService svc;
    private final MeliAuthService auth;
    // Daemon threads: auditoria de fundo não pode segurar o shutdown da JVM.
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "quality-jobs");
        t.setDaemon(true);
        return t;
    });

    private final Set<Long> inProgress = ConcurrentHashMap.newKeySet();
    private final Map<Long, Map<String, String>> itemRecheckPending = new ConcurrentHashMap<>();
    private final Set<Long> itemRecheckWorkers = ConcurrentHashMap.newKeySet();

    public QualityJobs(MeliQualityService svc, MeliAuthService auth) {
        this.svc = svc;
        this.auth = auth;
    }

    public boolean isRunning(long userId) {
        return inProgress.contains(userId);
    }

    /** Enfileira; retorna false quando a conta já está sendo processada. */
    public boolean enqueueQuality(long userId, String nickname) {
        if (!inProgress.add(userId)) return false;
        pool.execute(() -> runQuality(userId, nickname));
        return true;
    }

    private void runQuality(long userId, String nickname) {
        try {
            svc.build(userId, nickname);
        } catch (Exception exc) {
            log.warn("quality: auditoria user={} falhou: {}", userId, exc.getMessage());
            try {
                ObjectNode snapshot = svc.load(userId);
                if (snapshot != null) {
                    snapshot.put("status", "error");
                    snapshot.withArray("warnings")
                            .add("A auditoria foi interrompida. Tente atualizar novamente.");
                    svc.save(snapshot);
                }
            } catch (Exception ignored) {
                // cache local; nada externo aqui.
            }
        } finally {
            inProgress.remove(userId);
        }
    }

    /** Agrupa revalidações do mesmo anúncio e inicia um worker por conta. */
    public int enqueueItemRevalidation(long userId, List<String> itemIds, String source) {
        List<String> cleanIds = new ArrayList<>();
        for (String id : itemIds) if (id != null && !id.isBlank()) cleanIds.add(id);
        if (cleanIds.isEmpty()) return 0;

        // Marca imediatamente no cache; nenhuma chamada externa acontece aqui.
        if (!svc.markValidating(userId, cleanIds, source)) {
            // Sem snapshot ainda: a auditoria noturna será a primeira fonte de dados.
            return 0;
        }

        Map<String, String> pending = itemRecheckPending.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
        for (String id : cleanIds) pending.put(id, source);

        if (itemRecheckWorkers.add(userId)) {
            pool.execute(() -> itemRecheckWorker(userId));
        }
        return cleanIds.size();
    }

    private void itemRecheckWorker(long userId) {
        try {
            sleep(ITEM_RECHECK_DELAYS_MS[0]); // debounce: um save do BulkEdit dispara várias rotas
            Map<String, String> pending = itemRecheckPending.get(userId);
            while (pending != null && !pending.isEmpty()) {
                while (isRunning(userId)) sleep(5_000); // não briga com a auditoria full

                String itemId = popOne(pending);
                if (itemId == null) break;

                Set<String> before = svc.cachedIssueKeys(userId, itemId);
                Exception lastError = null;
                boolean settled = false;
                for (int attempt = 0; attempt < ITEM_RECHECK_DELAYS_MS.length; attempt++) {
                    if (attempt > 0) sleep(ITEM_RECHECK_DELAYS_MS[attempt]);
                    ObjectNode audited;
                    try {
                        audited = svc.auditOne(userId, itemId);
                    } catch (Exception exc) {
                        lastError = exc;
                        log.warn("quality: revalidacao user={} item={} tentativa={} falhou: {}",
                                userId, itemId, attempt + 1, exc.getMessage());
                        continue;
                    }
                    Set<String> after = issueKeys(audited);
                    boolean changed = !after.equals(before);
                    boolean isLast = attempt == ITEM_RECHECK_DELAYS_MS.length - 1;
                    svc.applyItemAudit(userId, itemId, audited, changed || isLast ? "confirmed" : "validating");
                    if (changed || audited == null || isLast) {
                        settled = true;
                        break;
                    }
                }
                if (!settled) {
                    svc.markValidationFailed(userId, itemId,
                            "Nao foi possivel confirmar as alteracoes agora. O ultimo resultado foi mantido.");
                    if (lastError != null) {
                        log.warn("quality: revalidacao user={} item={} encerrada sem confirmacao: {}",
                                userId, itemId, lastError.getMessage());
                    }
                }
                pending = itemRecheckPending.get(userId);
            }
        } catch (Exception exc) {
            log.warn("quality: worker incremental user={} falhou: {}", userId, exc.getMessage());
        } finally {
            itemRecheckWorkers.remove(userId);
            Map<String, String> remaining = itemRecheckPending.get(userId);
            if (remaining != null && !remaining.isEmpty() && itemRecheckWorkers.add(userId)) {
                pool.execute(() -> itemRecheckWorker(userId));
            }
        }
    }

    /** Executa todas as contas sequencialmente na janela da madrugada. */
    @Scheduled(cron = "${quality.audit-cron:0 0 3 * * *}")
    public void runNightlyQualityAudits() {
        pool.execute(() -> {
            log.info("quality: iniciando auditoria noturna");
            for (Map<String, Object> account : auth.listAccounts()) {
                long userId = number(account.get("user_id"));
                if (!inProgress.add(userId)) {
                    log.info("quality: user={} ja esta na fila; pulando job noturno", userId);
                    continue;
                }
                runQuality(userId, String.valueOf(account.getOrDefault("nickname", "")));
            }
            log.info("quality: auditoria noturna concluida");
        });
    }

    /** Retoma confirmações que estavam pendentes antes de um restart. */
    @EventListener(ApplicationReadyEvent.class)
    public void resumePendingItemRevalidations() {
        pool.execute(() -> {
            int resumed = 0;
            for (Map<String, Object> account : auth.listAccounts()) {
                long userId = number(account.get("user_id"));
                ObjectNode snapshot = svc.load(userId);
                if (snapshot == null) continue;
                List<String> itemIds = new ArrayList<>();
                for (var id : snapshot.path("validating_item_ids")) {
                    if (!id.asText("").isEmpty()) itemIds.add(id.asText());
                }
                if (!itemIds.isEmpty()) resumed += enqueueItemRevalidation(userId, itemIds, "startup_resume");
            }
            if (resumed > 0) log.info("quality: {} revalidacoes incrementais retomadas", resumed);
        });
    }

    // ---------- helpers ----------

    private static String popOne(Map<String, String> pending) {
        for (String key : pending.keySet()) {
            if (pending.remove(key) != null) return key;
        }
        return null;
    }

    private static Set<String> issueKeys(ObjectNode audited) {
        Set<String> keys = new HashSet<>();   // local a uma thread; não precisa ser concorrente
        if (audited != null) {
            for (var issue : audited.path("issues")) {
                String k = issue.path("key").asText(null);
                if (k != null && !k.isBlank()) keys.add(k);
            }
        }
        return keys;
    }

    private static long number(Object value) {
        if (value instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
