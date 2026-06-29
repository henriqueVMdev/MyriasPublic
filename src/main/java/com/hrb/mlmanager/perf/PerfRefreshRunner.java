package com.hrb.mlmanager.perf;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Refresh de snapshot em background. Espelho do {@code asyncio.create_task} +
 * {@code _refresh_in_progress} do api/performance.py: evita empilhar varreduras
 * (F5 repetido, várias abas). Set em memória — vale para 1 worker, mesmo
 * racional das sessões/contas-em-progresso do Python.
 */
@Component
public class PerfRefreshRunner {

    private static final Logger log = LoggerFactory.getLogger(PerfRefreshRunner.class);

    private final MeliPerformanceService svc;
    private final Set<Long> inProgress = ConcurrentHashMap.newKeySet();

    public PerfRefreshRunner(MeliPerformanceService svc) {
        this.svc = svc;
    }

    public boolean isRunning(long userId) {
        return inProgress.contains(userId);
    }

    /** Marca a conta como em-progresso; false se já estava (anti-stack). Atômico. */
    public boolean tryAcquire(long userId) {
        return inProgress.add(userId);
    }

    /**
     * Reconstrói o snapshot em outra thread. Chamado pelo controller APÓS
     * {@link #tryAcquire} (cross-bean, então o proxy @Async aplica). Sempre
     * libera o slot no fim, mesmo em falha.
     */
    @Async
    public void runBackground(long userId, String nickname, int lookbackDays, String mode) {
        try {
            svc.refreshSnapshot(userId, nickname, lookbackDays, mode);
        } catch (Exception e) {
            log.warn("perf: bg refresh user={} falhou: {}", userId, e.getMessage());
        } finally {
            inProgress.remove(userId);
        }
    }
}
