package com.myrias.mlmanager.meli;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Cliente HTTP da API do Mercado Livre com auth, rate limit e retry.
 * Espelho de backend/app/services/meli_client.py.
 *
 * O async do Python (httpx + asyncio) vira síncrono: cada chamada roda na thread
 * do request e o {@link MeliRateLimiter} bloqueia a thread em vez de uma
 * coroutine. O paralelismo do {@code asyncio.gather} em {@link #multiGetItems}
 * vira um {@link ExecutorService} — mas o teto real de concorrência continua
 * sendo o semáforo do limiter, não o tamanho do pool.
 */
@Component
public class MeliClient {

    private static final Logger log = LoggerFactory.getLogger(MeliClient.class);

    private static final String BASE_URL = "https://api.mercadolibre.com";
    private static final int MAX_RETRIES = 3;
    private static final int BACKOFF_BASE = 2;
    private static final int MULTIGET_BATCH = 20; // limite do /items?ids=

    private final MeliAuthService auth;
    private final MeliRateLimiter limiter;
    private final RestClient http;
    private final ExecutorService pool;

    public MeliClient(MeliAuthService auth, MeliRateLimiter limiter,
                      @Value("${meli.max-concurrent-requests:10}") int maxConcurrent) {
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
        // Pool dimensionado pela concorrência do limiter: passar disso só geraria
        // threads paradas em acquire(). ponytail: fixo; o gargalo é o semáforo.
        this.pool = Executors.newFixedThreadPool(Math.max(1, maxConcurrent));
    }

    /** Resposta crua: status HTTP + corpo JSON (null se não for JSON). */
    public record MeliResponse(int status, JsonNode data) {}

    // ---- Verbos --------------------------------------------------------------

    public MeliResponse request(HttpMethod method, String path, Long userId, Object body) {
        return request(method, path, userId, body, null);
    }

    /** Variante com headers extras (ex.: Api-Version pras rotas de Mercado Ads). */
    public MeliResponse request(HttpMethod method, String path, Long userId, Object body,
                                Map<String, String> headers) {
        boolean didTokenRefresh = false;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            limiter.acquire();
            try {
                String token = auth.ensureValidToken(userId);
                MeliResponse resp = send(method, path, "Bearer " + token, body, headers);

                // 401 = token inválido/expirado. Rede de segurança: invalida o
                // cache e força refresh numa única retentativa.
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

                // 429 = rate limited; 423 = recurso travado (comum em DELETEs no
                // mesmo user-product); 409 = conflito de versão no KVS (FURY_MULTI_BATCH),
                // comum ao editar irmãos do mesmo user_product/family em sequência —
                // transitório, o próximo attempt costuma passar. 5xx = erro do servidor.
                if (resp.status() == 429 || resp.status() == 423 || resp.status() == 409) {
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

    public MeliResponse get(String path, Map<String, String> params, Long userId) {
        return request(HttpMethod.GET, withQuery(path, params), userId, null);
    }

    public MeliResponse get(String path, Map<String, String> params, Map<String, String> headers, Long userId) {
        return request(HttpMethod.GET, withQuery(path, params), userId, null, headers);
    }

    public MeliResponse put(String path, Long userId, Object body) {
        return request(HttpMethod.PUT, path, userId, body);
    }

    public MeliResponse put(String path, Map<String, String> params, Long userId, Object body) {
        return request(HttpMethod.PUT, withQuery(path, params), userId, body);
    }

    public MeliResponse post(String path, Long userId, Object body) {
        return request(HttpMethod.POST, path, userId, body);
    }

    public MeliResponse post(String path, Map<String, String> params, Long userId, Object body) {
        return request(HttpMethod.POST, withQuery(path, params), userId, body);
    }

    public MeliResponse delete(String path, Long userId) {
        return request(HttpMethod.DELETE, path, userId, null);
    }

    public MeliResponse delete(String path, Map<String, String> params, Long userId) {
        return request(HttpMethod.DELETE, withQuery(path, params), userId, null);
    }

    public MeliResponse delete(String path, Long userId, Object body) {
        return request(HttpMethod.DELETE, path, userId, body);
    }

    public MeliResponse delete(String path, Map<String, String> params, Long userId, Object body) {
        return request(HttpMethod.DELETE, withQuery(path, params), userId, body);
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

    // ---- Operações de alto nível (ex meli_client.py) -------------------------

    /**
     * Busca até 20 itens por chamada, em batches paralelos. Retorna os corpos
     * dos itens cujo {@code code == 200}. Espelho de multi_get_items: o gather
     * vira CompletableFuture, mas o limiter é quem regula a concorrência real.
     */
    public List<JsonNode> multiGetItems(List<String> itemIds, List<String> fields, Long userId) {
        List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < itemIds.size(); i += MULTIGET_BATCH) {
            batches.add(itemIds.subList(i, Math.min(i + MULTIGET_BATCH, itemIds.size())));
        }

        List<CompletableFuture<List<JsonNode>>> futures = new ArrayList<>();
        for (List<String> batch : batches) {
            futures.add(CompletableFuture.supplyAsync(() -> fetchBatch(batch, fields, userId), pool));
        }

        List<JsonNode> out = new ArrayList<>();
        for (CompletableFuture<List<JsonNode>> f : futures) { // ordem das batches preservada
            out.addAll(f.join());
        }
        return out;
    }

    /**
     * Aplica {@code fn} a cada item em paralelo (mesmo pool do multiGet), preservando
     * a ordem. Substitui o {@code asyncio.gather} usado nos fan-outs de
     * visitas/ads do serviço de performance; a vazão real segue limitada pelo
     * rate limiter dentro de cada chamada.
     */
    public <T, R> List<R> parallelMap(List<T> items, java.util.function.Function<T, R> fn) {
        List<CompletableFuture<R>> futures = new ArrayList<>();
        for (T it : items) futures.add(CompletableFuture.supplyAsync(() -> fn.apply(it), pool));
        List<R> out = new ArrayList<>(items.size());
        for (CompletableFuture<R> f : futures) out.add(f.join());
        return out;
    }

    private List<JsonNode> fetchBatch(List<String> batch, List<String> fields, Long userId) {
        Map<String, String> params = new java.util.LinkedHashMap<>();
        params.put("ids", String.join(",", batch));
        if (fields != null && !fields.isEmpty()) {
            params.put("attributes", String.join(",", fields));
        }
        MeliResponse resp = get("/items", params, userId);
        Map<String, JsonNode> bodiesById = new LinkedHashMap<>();

        if (resp.data() != null && resp.data().isArray()) {
            int index = 0;
            for (JsonNode entry : resp.data()) {
                String expectedId = index < batch.size() ? batch.get(index) : null;
                index++;

                int itemCode = entry.path("code").asInt(resp.status());
                JsonNode body = entry.get("body");
                if (itemCode == 200 && body != null && body.isObject()) {
                    String itemId = expectedId;
                    if (itemId == null || itemId.isBlank()) {
                        itemId = body.path("id").asText("");
                    }
                    if (!itemId.isBlank()) {
                        JsonNode selected = body;
                        if (attributesRequested(fields) && attributesMissing(body)) {
                            JsonNode detailed = fetchItemIndividually(itemId, true, userId);
                            if (detailed != null) {
                                selected = detailed;
                            } else {
                                log.warn("Multiget item {} veio sem attributes e o refetch individual falhou; "
                                        + "mantendo o corpo parcial", itemId);
                            }
                        }
                        bodiesById.put(itemId, selected);
                    }
                } else {
                    log.warn("Multiget item {} rejeitado: code={} detail={}",
                            expectedId == null ? "desconhecido" : expectedId,
                            itemCode, responseDetail(entry));
                }
            }
        } else {
            String dataType = resp.data() == null ? "null" : resp.data().getNodeType().name();
            log.warn("Multiget de {} item(ns) retornou HTTP {} com corpo {}; "
                    + "tentando consultas individuais", batch.size(), resp.status(), dataType);
        }

        // O multiget pode responder HTTP 200 e ainda devolver code=403/404 por
        // item. Tenta o endpoint individual em vez de transformar a falha numa
        // lista vazia, comportamento que escondia o problema no frontend.
        for (String itemId : batch) {
            if (bodiesById.containsKey(itemId)) continue;
            JsonNode fallback = fetchItemIndividually(itemId, attributesRequested(fields), userId);
            if (fallback != null) {
                bodiesById.put(itemId, fallback);
            }
        }

        List<JsonNode> bodies = new ArrayList<>();
        for (String itemId : batch) {
            JsonNode body = bodiesById.get(itemId);
            if (body != null) bodies.add(body);
        }
        return bodies;
    }

    private JsonNode fetchItemIndividually(String itemId, boolean includeAllAttributes, Long userId) {
        MeliResponse resp;
        if (includeAllAttributes) {
            resp = get("/items/" + itemId, Map.of("include_attributes", "all"), userId);
        } else {
            resp = get("/items/" + itemId, userId);
        }
        if (resp.status() == 200 && resp.data() != null && resp.data().isObject()) {
            log.info("Fallback individual recuperou item {}", itemId);
            return resp.data();
        }
        log.warn("Fallback individual do item {} falhou: HTTP {} detail={}",
                itemId, resp.status(), responseDetail(resp.data()));
        return null;
    }

    private static boolean attributesRequested(List<String> fields) {
        return fields != null && fields.contains("attributes");
    }

    private static boolean attributesMissing(JsonNode body) {
        JsonNode attributes = body.get("attributes");
        return attributes == null || !attributes.isArray() || attributes.isEmpty();
    }

    private static String responseDetail(JsonNode node) {
        if (node == null || node.isNull()) return "sem corpo";
        for (String pointer : List.of("/body/message", "/body/error", "/message", "/error")) {
            JsonNode value = node.at(pointer);
            if (!value.isMissingNode() && !value.isNull() && !value.asText().isBlank()) {
                return abbreviate(value.asText());
            }
        }
        return abbreviate(node.isValueNode() ? node.asText() : node.toString());
    }

    private static String abbreviate(String value) {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return clean.length() <= 200 ? clean : clean.substring(0, 200) + "...";
    }

    /**
     * Todos os IDs de itens da conta via scroll (search_type=scan), sequencial.
     * Espelho de scan_all_items.
     */
    public List<String> scanAllItems(Long userId, String status, Integer limit) {
        List<String> all = new ArrayList<>();
        Map<String, String> params = new java.util.LinkedHashMap<>();
        params.put("search_type", "scan");
        if (status != null) params.put("status", status);
        if (limit != null) params.put("limit", String.valueOf(limit));

        MeliResponse resp = get("/users/" + userId + "/items/search", params, userId);
        collectResults(resp, all);
        String scrollId = scrollId(resp);

        while (scrollId != null) {
            params.put("scroll_id", scrollId);
            resp = get("/users/" + userId + "/items/search", params, userId);
            collectResults(resp, all);
            scrollId = scrollId(resp);
        }
        return all;
    }

    private static void collectResults(MeliResponse resp, List<String> out) {
        if (resp.data() == null) return;
        JsonNode results = resp.data().path("results");
        if (results.isArray()) {
            results.forEach(n -> out.add(n.asText()));
        }
    }

    private static String scrollId(MeliResponse resp) {
        if (resp.data() == null) return null;
        JsonNode s = resp.data().get("scroll_id");
        return (s == null || s.isNull() || s.asText().isEmpty()) ? null : s.asText();
    }

    /** Upload de imagem para o ML, retorna dados com a source URL. Espelho de upload_picture. */
    public MeliResponse uploadPicture(byte[] fileBytes, String filename) {
        limiter.acquire();
        try {
            String token = auth.ensureValidToken(null);
            ByteArrayResource part = new ByteArrayResource(fileBytes) {
                @Override
                public String getFilename() {
                    return filename;
                }
            };
            MultiValueMap<String, Object> body = new org.springframework.util.LinkedMultiValueMap<>();
            body.add("file", part);

            MeliResponse resp = http.post()
                    .uri("/pictures/items/upload")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .exchange((req, res) -> new MeliResponse(res.getStatusCode().value(), readJson(res)), false);
            log.info("POST /pictures/items/upload → {}", resp.status());
            return resp;
        } finally {
            limiter.release();
        }
    }

    // ---- Internos ------------------------------------------------------------

    private MeliResponse send(HttpMethod method, String path, String authHeader, Object body,
                              Map<String, String> headers) {
        RestClient.RequestBodySpec spec = http.method(method).uri(path).header("Authorization", authHeader);
        if (headers != null) {
            for (Map.Entry<String, String> h : headers.entrySet()) spec = spec.header(h.getKey(), h.getValue());
        }
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

    private static String withQuery(String path, Map<String, String> params) {
        if (params == null || params.isEmpty()) return path;
        StringBuilder sb = new StringBuilder(path).append(path.contains("?") ? '&' : '?');
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            first = false;
        }
        return sb.toString();
    }

    private void sleepBackoff(int seconds, int status, HttpMethod method, String path) {
        log.warn("{} retry in {}s — {} {}", status, seconds, method, path);
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void close() {
        pool.shutdownNow();
    }
}
