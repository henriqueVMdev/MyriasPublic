package com.myrias.mlmanager.meli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MeliRateLimiterTest {

    /** Com 1 slot, a segunda thread só entra depois do release da primeira. */
    @Test
    void slotOcupadoBloqueiaAteORelease() throws Exception {
        MeliRateLimiter limiter = new MeliRateLimiter(60_000, 1); // intervalo 1ms
        limiter.acquire();

        CountDownLatch entrou = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> {
                limiter.acquire();
                entrou.countDown();
                limiter.release();
            });

            assertFalse(entrou.await(200, TimeUnit.MILLISECONDS),
                    "segunda thread não podia passar com o único slot ocupado");
            limiter.release();
            assertTrue(entrou.await(2, TimeUnit.SECONDS),
                    "segunda thread devia entrar após o release");
        } finally {
            pool.shutdownNow();
        }
    }

    /** Cadência: 600 rpm = 100ms entre disparos, então 3 acquires levam ≥200ms. */
    @Test
    void respeitaIntervaloMinimoEntreDisparos() {
        MeliRateLimiter limiter = new MeliRateLimiter(600, 5);
        long inicio = System.nanoTime();
        for (int i = 0; i < 3; i++) {
            limiter.acquire();
            limiter.release();
        }
        long decorridoMs = (System.nanoTime() - inicio) / 1_000_000L;
        assertTrue(decorridoMs >= 180, "esperava >=180ms de espaçamento, foi " + decorridoMs + "ms");
    }
}
