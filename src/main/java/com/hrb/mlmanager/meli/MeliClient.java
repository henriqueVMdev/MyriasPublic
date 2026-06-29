package com.hrb.mlmanager.meli;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Cliente HTTP da API do Mercado Livre com auth, rate limit e retry.
 * Espelho de backend/app/services/meli_client.py.
 *
 * O async do Python (httpx + asyncio) vira síncrono aqui: cada chamada roda na
 * thread do request, o {@link MeliRateLimiter} bloqueia a thread em vez de uma
 * coroutine. {@code multi_get_items}, {@code scan_all_items} e
 * {@code upload_picture} ficam para a fatia de items — ponytail: YAGNI até haver
 * chamador (lá entra o paralelismo do asyncio.gather, via ExecutorService).
 */
@Component
public class MeliClient {

    private static final Logger log = LoggerFactory.getLogger(MeliClient.class);

    private static final String BASE_URL = "https://api.mercadolibre.com";
    private static final int MAX_RETRIES = 3;
    private static final int BACKOFF_BASE = 2;

    private final MeliAuthService auth;
    private final MeliRateLimiter limiter;
    private final RestClient http;

    public MeliClient(MeliAuthService auth, MeliRateLimiter limiter) {
        this.auth = auth;
        this.limiter = limiter;
        // Timeout granular como no Python: 10s pra conectar, 60s pra resposta —
        // ML estoura 30s em rotas pesadas (compatibilities, user-products) no pico.
        var settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(10))
                .withReadTimeout(Duration.ofSeconds(60));
        this.http = RestClient.builder()
                .baseUrl(BASE_URL)
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }

    /** Resposta crua: status HTTP + corpo JSON (null se não for JSON). */
    public record MeliResponse(int status, JsonNode data) {}

    /**
     * Executa a request com auth, rate limit e retry. {@code userId} seleciona
     * qual conta ML usar (null = conta ativa). {@code body} vira corpo JSON
     * quando não-null.
     */
    public MeliResponse request(HttpMethod method, String path, Long userId, Object body) {
        boolean didTokenRefresh = false;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            limiter.acquire();
            try {
                String token = auth.ensureValidToken(userId);
                MeliResponse resp = send(method, path, "Bearer " + token, body);

                // 401 = token inválido/expirado. Rede de segurança: invalida o
                // cache e força refresh numa única retentativa (caso o refresh
                // proativo não tenha pego — relógio defasado, revogação no ML).
                if (resp.status() == 401 && !didTokenRefresh) {
                    didTokenRefresh = true;
                    log.warn("401 em {} {} — forçando refresh do token", method, path);
                    String refreshed = auth.forceRefreshToken(userId);
                    if (refreshed == null) {
                        log.error("401 em {} {} e refresh falhou — sem recuperação", method, path);
                        return resp;
                    }
                    continue;
                }

                // 429 = rate limited; 423 = recurso travado (ML libera logo).
                // 5xx = erro do servidor. Em todos, backoff e retry.
                if (resp.status() == 429 || resp.status() == 423) {
                    sleepBackoff((int) Math.pow(BACKOFF_BASE, attempt + 1), resp.status(), method, path);
                    continue;
                }
                if (resp.status() >= 500) {
                    sleepBackoff((int) Math.pow(BACKOFF_BASE, attempt), resp.status(), method, path);
                    continue;
                }

                log.info("{} {} → {}", method, path, resp.status());
                return resp;
            } finally {
                limiter.release();
            }
        }
        throw new IllegalStateException("Max retries exceeded for " + method + " " + path);
    }

    public MeliResponse get(String path, Long userId) {
        return request(HttpMethod.GET, path, userId, null);
    }

    public MeliResponse put(String path, Long userId, Object body) {
        return request(HttpMethod.PUT, path, userId, body);
    }

    public MeliResponse post(String path, Long userId, Object body) {
        return request(HttpMethod.POST, path, userId, body);
    }

    public MeliResponse delete(String path, Long userId) {
        return request(HttpMethod.DELETE, path, userId, null);
    }

    /** GET sem autenticação (dados públicos) com User-Agent de navegador. */
    public MeliResponse getPublic(String path) {
        limiter.acquire();
        try {
            MeliResponse resp = http.get()
                    .uri(path)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                    .header("Accept", "application/json")
                    .exchange((req, res) -> new MeliResponse(res.getStatusCode().value(), readJson(res)), false);
            log.info("GET (public) {} → {}", path, resp.status());
            return resp;
        } finally {
            limiter.release();
        }
    }

    // ---- Internos ------------------------------------------------------------

    private MeliResponse send(HttpMethod method, String path, String authHeader, Object body) {
        RestClient.RequestBodySpec spec = http.method(method).uri(path).header("Authorization", authHeader);
        if (body != null) {
            spec = spec.body(body);
        }
        // exchange (não retrieve) para NÃO lançar em 4xx/5xx — o status volta no
        // record e o retry trata, igual ao httpx do Python.
        return spec.exchange((req, res) -> new MeliResponse(res.getStatusCode().value(), readJson(res)), false);
    }

    private static JsonNode readJson(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse res) {
        try {
            return res.bodyTo(JsonNode.class);
        } catch (Exception e) {
            return null; // resposta sem JSON (ex: corpo vazio)
        }
    }

    private void sleepBackoff(int seconds, int status, HttpMethod method, String path) {
        log.warn("{} retry in {}s — {} {}", status, seconds, method, path);
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
