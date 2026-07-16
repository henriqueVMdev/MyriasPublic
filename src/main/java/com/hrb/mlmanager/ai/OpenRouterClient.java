package com.hrb.mlmanager.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Cliente síncrono do OpenRouter para chat e catálogo de modelos. */
@Component
public class OpenRouterClient {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterClient.class);
    private static final Duration CATALOG_CACHE_TTL = Duration.ofMinutes(15);

    private final RestClient http;
    private final String apiKey;
    private final String defaultModel;
    private final List<String> models;
    private volatile List<ModelInfo> catalogCache = List.of();
    private volatile Instant catalogCacheAt = Instant.EPOCH;

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

    public record ModelInfo(
            String id,
            String name,
            String description,
            long created,
            int contextLength,
            Map<String, String> pricing,
            List<String> supportedParameters,
            List<String> inputModalities,
            List<String> outputModalities,
            String tokenizer,
            String instructType,
            Integer maxCompletionTokens,
            String knowledgeCutoff,
            JsonNode defaultParameters,
            boolean free,
            boolean toolCompatible) {}

    /**
     * Modelos utilizáveis pelo agente atual, que depende de function calling.
     * O painel administrativo usa {@link #allModels()} para mostrar o catálogo
     * completo, inclusive modelos incompatíveis.
     */
    public List<ModelOption> availableModels() {
        return allModels().stream()
                .filter(ModelInfo::toolCompatible)
                .map(model -> new ModelOption(model.id(), model.name()))
                .toList();
    }

    /** Catálogo completo do OpenRouter, com metadados e preços. */
    public List<ModelInfo> allModels() {
        Instant now = Instant.now();
        List<ModelInfo> cached = catalogCache;
        if (!cached.isEmpty() && now.isBefore(catalogCacheAt.plus(CATALOG_CACHE_TTL))) {
            return cached;
        }
        synchronized (this) {
            now = Instant.now();
            if (!catalogCache.isEmpty() && now.isBefore(catalogCacheAt.plus(CATALOG_CACHE_TTL))) {
                return catalogCache;
            }
            List<ModelInfo> loaded = fetchAllModels();
            catalogCache = loaded;
            catalogCacheAt = now;
            return loaded;
        }
    }

    public List<ModelInfo> refreshModels() {
        synchronized (this) {
            catalogCache = List.of();
            catalogCacheAt = Instant.EPOCH;
        }
        return allModels();
    }

    /** Compatibilidade com chamadas antigas que aceitavam um modelo do cliente. */
    public String resolveModel(String requested) {
        return requested != null && models.contains(requested) ? requested : defaultModel;
    }

    private record Result(int status, JsonNode body) {}

    private List<ModelInfo> fetchAllModels() {
        Map<String, ModelInfo> found = new LinkedHashMap<>();
        try {
            Result resp = http.get()
                    .uri("/models?output_modalities=all&sort=most-popular")
                    .headers(headers -> {
                        if (apiKey != null && !apiKey.isBlank()) {
                            headers.setBearerAuth(apiKey);
                        }
                    })
                    .exchange((req, res) ->
                            new Result(res.getStatusCode().value(), readJson(res)), false);
            if (resp.status() == 200 && resp.body() != null) {
                for (JsonNode model : resp.body().path("data")) {
                    ModelInfo parsed = parseModel(model);
                    if (parsed != null) found.putIfAbsent(parsed.id(), parsed);
                }
            } else {
                log.warn("OpenRouter models retornou {}: {}", resp.status(), resp.body());
            }
        } catch (Exception e) {
            log.warn("Falha carregando catálogo de modelos OpenRouter: {}", e.getMessage());
        }

        // Mantém o app operável se o catálogo externo estiver indisponível.
        for (String id : models) {
            if (id != null && !id.isBlank()) found.putIfAbsent(id, fallbackModel(id));
        }
        if (defaultModel != null && !defaultModel.isBlank()) {
            found.putIfAbsent(defaultModel, fallbackModel(defaultModel));
        }
        return List.copyOf(found.values());
    }

    private static ModelInfo parseModel(JsonNode model) {
        String id = model.path("id").asText("");
        if (id.isBlank()) return null;

        JsonNode architecture = model.path("architecture");
        Map<String, String> pricing = new LinkedHashMap<>();
        model.path("pricing").fields().forEachRemaining(entry ->
                pricing.put(entry.getKey(), entry.getValue().asText("")));
        List<String> supported = textList(model.path("supported_parameters"));
        List<String> inputs = textList(architecture.path("input_modalities"));
        List<String> outputs = textList(architecture.path("output_modalities"));
        boolean free = id.endsWith(":free")
                || (isZero(pricing.get("prompt"))
                    && isZero(pricing.get("completion"))
                    && isZero(pricing.get("request")));
        boolean toolCompatible = supported.contains("tools") && outputs.contains("text");

        JsonNode defaultParameters = model.path("default_parameters");
        if (defaultParameters.isMissingNode() || defaultParameters.isNull()) {
            defaultParameters = null;
        } else {
            defaultParameters = defaultParameters.deepCopy();
        }
        JsonNode maxCompletion = model.path("top_provider").path("max_completion_tokens");

        return new ModelInfo(
                id,
                model.path("name").asText(id),
                model.path("description").asText(""),
                model.path("created").asLong(0),
                model.path("context_length").asInt(0),
                Map.copyOf(pricing),
                List.copyOf(supported),
                List.copyOf(inputs),
                List.copyOf(outputs),
                architecture.path("tokenizer").asText(""),
                architecture.path("instruct_type").asText(""),
                maxCompletion.isNumber() ? maxCompletion.asInt() : null,
                model.path("knowledge_cutoff").asText(""),
                defaultParameters,
                free,
                toolCompatible);
    }

    private static ModelInfo fallbackModel(String id) {
        return new ModelInfo(id, id, "Modelo configurado localmente.", 0, 0,
                Map.of(), List.of("tools"), List.of("text"), List.of("text"),
                "", "", null, "", null, id.endsWith(":free"), true);
    }

    private static List<String> textList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode value : node) {
                String text = value.asText("");
                if (!text.isBlank()) values.add(text);
            }
        }
        return values;
    }

    private static boolean isZero(String value) {
        if (value == null || value.isBlank()) return true;
        try {
            return new BigDecimal(value).compareTo(BigDecimal.ZERO) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
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
