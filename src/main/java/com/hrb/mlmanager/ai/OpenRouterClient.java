package com.hrb.mlmanager.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Cliente do OpenRouter (API compatível com OpenAI chat/completions).
 * Mesmo estilo do MeliClient: síncrono, resposta crua em JsonNode.
 * Erros HTTP viram OpenRouterException com mensagem amigável (vai pro chat).
 */
@Component
public class OpenRouterClient {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterClient.class);

    private final RestClient http;
    private final String apiKey;
    private final String defaultModel;
    private final List<String> models;
    private volatile List<ModelOption> catalogCache = List.of();
    private volatile Instant catalogCacheAt = Instant.EPOCH;

    private static final Duration CATALOG_CACHE_TTL = Duration.ofMinutes(10);

    public OpenRouterClient(RestClient openRouterRestClient,
                            @Value("${openrouter.api-key:}") String apiKey,
                            @Value("${openrouter.model}") String defaultModel,
                            @Value("${openrouter.models}") List<String> models) {
        this.http = openRouterRestClient;
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
        this.models = List.copyOf(models);
    }

    public String defaultModel() { return defaultModel; }

    public List<String> models() { return models; }

    public record ModelOption(String id, String name) {}

    /**
     * Catálogo dinâmico de modelos de texto com suporte a tools. Se a consulta
     * falhar, mantém os modelos configurados em OPENROUTER_MODELS.
     */
    public List<ModelOption> availableModels() {
        Instant now = Instant.now();
        List<ModelOption> cached = catalogCache;
        if (!cached.isEmpty() && now.isBefore(catalogCacheAt.plus(CATALOG_CACHE_TTL))) {
            return cached;
        }
        synchronized (this) {
            now = Instant.now();
            if (!catalogCache.isEmpty() && now.isBefore(catalogCacheAt.plus(CATALOG_CACHE_TTL))) {
                return catalogCache;
            }
            List<ModelOption> loaded = fetchAvailableModels();
            catalogCache = loaded;
            catalogCacheAt = now;
            return loaded;
        }
    }

    /** Modelo pedido pelo widget se estiver na lista; senão o default. */
    public String resolveModel(String requested) {
        return requested != null && models.contains(requested) ? requested : defaultModel;
    }

    private record Result(int status, JsonNode body) {}

    private List<ModelOption> fetchAvailableModels() {
        Map<String, ModelOption> found = new LinkedHashMap<>();
        if (apiKey != null && !apiKey.isBlank()) {
            try {
                Result resp = http.get()
                        .uri("/models?supported_parameters=tools&output_modalities=text&sort=most-popular")
                        .header("Authorization", "Bearer " + apiKey)
                        .exchange((req, res) ->
                                new Result(res.getStatusCode().value(), readJson(res)), false);
                if (resp.status() == 200 && resp.body() != null) {
                    for (JsonNode model : resp.body().path("data")) {
                        String id = model.path("id").asText("");
                        if (id.isBlank()) continue;
                        String name = model.path("name").asText(id);
                        found.putIfAbsent(id, new ModelOption(id, name));
                    }
                } else {
                    log.warn("OpenRouter models retornou {}: {}", resp.status(), resp.body());
                }
            } catch (Exception e) {
                log.warn("Falha carregando catálogo de modelos OpenRouter: {}", e.getMessage());
            }
        }

        for (String id : models) {
            if (id != null && !id.isBlank()) {
                found.putIfAbsent(id, new ModelOption(id, id));
            }
        }
        if (defaultModel != null && !defaultModel.isBlank()) {
            found.putIfAbsent(defaultModel, new ModelOption(defaultModel, defaultModel));
        }
        return List.copyOf(found.values());
    }

    public JsonNode chat(ObjectNode payload) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new OpenRouterException("OPENROUTER_API_KEY não configurada no servidor.");
        }
        Result resp;
        try {
            resp = http.post().uri("/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("HTTP-Referer", "https://github.com/hrb/ml-manager")
                    .header("X-Title", "HRB ML Manager")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .exchange((req, res) -> new Result(res.getStatusCode().value(), readJson(res)), false);
        } catch (Exception e) {
            log.warn("OpenRouter inacessível: {}", e.getMessage());
            throw new OpenRouterException("Não consegui falar com o OpenRouter: " + e.getMessage());
        }
        if (resp.status() != 200) {
            log.warn("OpenRouter {} → {}", resp.status(), resp.body());
            throw new OpenRouterException(friendlyError(resp.status(), resp.body()));
        }
        return resp.body();
    }

    private static String friendlyError(int status, JsonNode body) {
        return switch (status) {
            case 401 -> "Chave do OpenRouter inválida (401). Confira OPENROUTER_API_KEY.";
            case 402 -> "Sem créditos no OpenRouter (402). Recarregue em openrouter.ai.";
            case 429 -> "Rate limit do OpenRouter (429). Aguarde um pouco e tente de novo.";
            default -> {
                String detail = body == null ? "" : body.path("error").path("message").asText("");
                yield "OpenRouter retornou erro " + status
                        + (detail.isBlank() ? "" : ": " + detail) + ". Tente de novo.";
            }
        };
    }

    private static JsonNode readJson(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse res) {
        try {
            return res.bodyTo(JsonNode.class);
        } catch (Exception e) {
            return null;
        }
    }
}
