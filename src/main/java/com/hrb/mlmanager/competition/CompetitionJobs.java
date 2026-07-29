package com.hrb.mlmanager.competition;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hrb.mlmanager.meli.MeliAuthService;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Coordena as varreduras de concorrência (manual + noturna). Versão enxuta do
 * {@link com.hrb.mlmanager.quality.QualityJobs}: só build de conta inteira, sem
 * revalidação incremental por item (o detalhe ao vivo cobre isso sob demanda).
 * Pool de daemons + o mesmo rate limiter compartilhado do MeliClient.
 */
@Component
public class CompetitionJobs {

    private static final Logger log = LoggerFactory.getLogger(CompetitionJobs.class);

    private final MeliCompetitionService svc;
    private final MeliAuthService auth;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "competition-jobs");
        t.setDaemon(true);
        return t;
    });
    private final Set<Long> inProgress = ConcurrentHashMap.newKeySet();

    public CompetitionJobs(MeliCompetitionService svc, MeliAuthService auth) {
        this.svc = svc;
        this.auth = auth;
    }

    public boolean isRunning(long userId) {
        return inProgress.contains(userId);
    }

    /** Enfileira; false se a conta já está sendo varrida. */
    public boolean enqueueBuild(long userId, String nickname) {
        if (!inProgress.add(userId)) return false;
        pool.execute(() -> runBuild(userId, nickname));
        return true;
    }

    private void runBuild(long userId, String nickname) {
        try {
            svc.build(userId, nickname);
        } catch (Exception exc) {
            log.warn("competition: varredura user={} falhou: {}", userId, exc.getMessage());
            try {
                ObjectNode snapshot = svc.load(userId);
                if (snapshot != null) {
                    snapshot.put("status", "error");
                    snapshot.withArray("warnings").add("A varredura foi interrompida. Tente atualizar novamente.");
                    svc.save(snapshot);
                }
            } catch (Exception ignored) {
                // cache local; nada externo aqui.
            }
        } finally {
            inProgress.remove(userId);
        }
    }

    /** Varredura noturna de todas as contas, sequencial (após a de qualidade, 04h). */
    @Scheduled(cron = "${competition.audit-cron:0 0 4 * * *}")
    public void runNightly() {
        pool.execute(() -> {
            log.info("competition: iniciando varredura noturna");
            for (Map<String, Object> account : auth.listAccounts()) {
                long userId = number(account.get("user_id"));
                if (!inProgress.add(userId)) continue;
                runBuild(userId, String.valueOf(account.getOrDefault("nickname", "")));
            }
            log.info("competition: varredura noturna concluida");
        });
    }

    private static long number(Object value) {
        if (value instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(value));
    }
}
