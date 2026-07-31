package com.myrias.mlmanager.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenRouterClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String URL = "https://openrouter.ai/api/v1/chat/completions";

    private record Fixture(OpenRouterClient client, MockRestServiceServer server) {}

    private Fixture fixture(String apiKey) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://openrouter.ai/api/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenRouterClient client = new OpenRouterClient(builder.build(), apiKey,
                "anthropic/claude-sonnet-4.5",
                List.of("anthropic/claude-sonnet-4.5", "openai/gpt-4.1"), false);
        return new Fixture(client, server);
    }

    @Test
    void chatEnviaAuthEDevolveBody() throws Exception {
        Fixture f = fixture("test-key");
        f.server().expect(requestTo(URL))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"oi\"}}]}",
                        MediaType.APPLICATION_JSON));
        var payload = MAPPER.createObjectNode();
        payload.put("model", "anthropic/claude-sonnet-4.5");
        JsonNode resp = f.client().chat(payload);
        assertEquals("oi", resp.path("choices").path(0).path("message").path("content").asText());
        f.server().verify();
    }

    @Test
    void chaveComAspasEEspacosEHigienizada() throws Exception {
        Fixture f = fixture(" \"test-key\" ");
        f.server().expect(requestTo(URL))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        f.client().chat(MAPPER.createObjectNode());
        f.server().verify();
    }

    @Test
    void erro402ViraMensagemAmigavel() {
        Fixture f = fixture("test-key");
        f.server().expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED).body("{}"));
        OpenRouterException e = assertThrows(OpenRouterException.class,
                () -> f.client().chat(MAPPER.createObjectNode()));
        assertTrue(e.getMessage().toLowerCase().contains("crédito"), e.getMessage());
    }

    @Test
    void erroGenericoIncluiMensagemDoBody() {
        Fixture f = fixture("test-key");
        f.server().expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"message\":\"model not found\"}}"));
        OpenRouterException e = assertThrows(OpenRouterException.class,
                () -> f.client().chat(MAPPER.createObjectNode()));
        assertTrue(e.getMessage().contains("400"), e.getMessage());
        assertTrue(e.getMessage().contains("model not found"), e.getMessage());
    }

    @Test
    void semApiKeyFalhaAntesDeChamar() {
        Fixture f = fixture("");
        OpenRouterException e = assertThrows(OpenRouterException.class,
                () -> f.client().chat(MAPPER.createObjectNode()));
        assertTrue(e.getMessage().contains("OPENROUTER_API_KEY"));
    }

    @Test
    void carregaCatalogoDinamicoSomenteComModelosDeTools() {
        Fixture f = fixture("test-key");
        f.server().expect(requestTo(
                        "https://openrouter.ai/api/v1/models?output_modalities=all&sort=most-popular"))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andRespond(withSuccess("""
                        {"data":[
                          {"id":"x/modelo-1","name":"Modelo Um",
                           "supported_parameters":["tools"],
                           "architecture":{"output_modalities":["text"]}},
                          {"id":"y/modelo-2","name":"Modelo Dois",
                           "supported_parameters":["tools"],
                           "architecture":{"output_modalities":["text"]}},
                          {"id":"z/sem-tools","name":"Sem Tools",
                           "architecture":{"output_modalities":["text"]}}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        List<OpenRouterClient.ModelOption> models = f.client().availableModels();

        assertEquals("x/modelo-1", models.get(0).id());
        assertEquals("Modelo Um", models.get(0).name());
        assertEquals("y/modelo-2", models.get(1).id());
        // Sem suporte a tools não pode ser usado pelo agente...
        assertTrue(models.stream().noneMatch(m -> m.id().equals("z/sem-tools")));
        // ...mas continua visível no catálogo completo do painel admin.
        assertTrue(f.client().allModels().stream().anyMatch(m -> m.id().equals("z/sem-tools")));
        f.server().verify();
    }

    @Test
    void catalogoUsaConfiguracaoComoFallbackSemApiKey() {
        Fixture f = fixture("");
        // O endpoint público é consultado mesmo sem api key; se falhar, cai no fallback local.
        f.server().expect(requestTo(
                        "https://openrouter.ai/api/v1/models?output_modalities=all&sort=most-popular"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("{}"));

        List<OpenRouterClient.ModelOption> models = f.client().availableModels();

        assertTrue(models.stream().anyMatch(m -> m.id().equals("anthropic/claude-sonnet-4.5")));
        assertTrue(models.stream().anyMatch(m -> m.id().equals("openai/gpt-4.1")));
    }

    /**
     * Versão pública/demo: nenhum modelo pago pode ser selecionado. Cobre as duas
     * formas de "grátis" que o parseModel reconhece — sufixo `:free` e preço zero.
     */
    @Test
    void soGratuitosFiltraModelosPagosDaSelecao() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://openrouter.ai/api/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenRouterClient client = new OpenRouterClient(builder.build(), "test-key",
                "gratis/por-sufixo:free", List.of("gratis/por-sufixo:free"), true);

        server.expect(requestTo(
                        "https://openrouter.ai/api/v1/models?output_modalities=all&sort=most-popular"))
                .andRespond(withSuccess("""
                        {"data":[
                          {"id":"pago/caro","name":"Pago",
                           "pricing":{"prompt":"0.000003","completion":"0.000015","request":"0"},
                           "supported_parameters":["tools"],
                           "architecture":{"output_modalities":["text"]}},
                          {"id":"gratis/por-sufixo:free","name":"Gratis Sufixo",
                           "pricing":{"prompt":"0.000003","completion":"0","request":"0"},
                           "supported_parameters":["tools"],
                           "architecture":{"output_modalities":["text"]}},
                          {"id":"gratis/por-preco","name":"Gratis Preco",
                           "pricing":{"prompt":"0","completion":"0","request":"0"},
                           "supported_parameters":["tools"],
                           "architecture":{"output_modalities":["text"]}}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        List<String> ids = client.availableModels().stream()
                .map(OpenRouterClient.ModelOption::id).toList();

        assertEquals(List.of("gratis/por-sufixo:free", "gratis/por-preco"), ids);
        // O pago segue visível no catálogo do admin — só não é selecionável.
        assertTrue(client.allModels().stream().anyMatch(m -> m.id().equals("pago/caro")));
        server.verify();
    }
}
