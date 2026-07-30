package com.myrias.mlmanager.meli;

import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Controla a pressão sobre a API do ML em duas dimensões, igual ao
 * backend/app/utils/rate_limiter.py:
 *   - concorrência: no máximo N requests simultâneas ({@link Semaphore});
 *   - cadência: intervalo mínimo entre disparos (60s / maxPerMinute).
 *
 * Em Spring MVC cada request roda na sua thread, então um {@code Semaphore}
 * bloqueante substitui o {@code asyncio.Semaphore} sem mudar a semântica:
 * a thread fica parada em acquire() até liberar um slot.
 *
 * ponytail: limite in-process, vale para 1 instância. Se escalar para várias
 * réplicas, mover a janela para um limiter distribuído (Redis).
 */
@Component
public class MeliRateLimiter {

    private final Semaphore semaphore;
    private final long intervalMillis;
    private final ReentrantLock paceLock = new ReentrantLock();
    private long lastRequestNanos = 0L;

    public MeliRateLimiter(
            @Value("${meli.max-requests-per-minute:1500}") int maxPerMinute,
            @Value("${meli.max-concurrent-requests:10}") int maxConcurrent) {
        this.semaphore = new Semaphore(maxConcurrent);
        this.intervalMillis = Math.round(60_000.0 / maxPerMinute);
    }

    /** Pega um slot de concorrência e respeita o intervalo mínimo entre disparos. */
    public void acquire() {
        semaphore.acquireUninterruptibly();
        // Espaçamento serializado sob lock — mesmo desenho do _lock async do Python:
        // segura o slot e só libera a próxima thread após o intervalo mínimo.
        paceLock.lock();
        try {
            long now = System.nanoTime();
            long waitMillis = intervalMillis - (now - lastRequestNanos) / 1_000_000L;
            if (waitMillis > 0) {
                try {
                    Thread.sleep(waitMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastRequestNanos = System.nanoTime();
        } catch (RuntimeException e) {
            semaphore.release();
            throw e;
        } finally {
            paceLock.unlock();
        }
    }

    public void release() {
        semaphore.release();
    }
}
