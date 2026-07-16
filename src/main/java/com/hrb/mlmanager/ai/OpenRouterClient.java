package com.hrb.mlmanager.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
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

    /** Modelo pedido pelo widget se estiver na lista; senão o default. */
    public String resolveModel(String requested) {
        return requested != null && models.contains(requested) ? requested : defaultModel;
    }

    private record Result(int status, JsonNode body) {}

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
