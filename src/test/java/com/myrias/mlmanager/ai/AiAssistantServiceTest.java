package com.myrias.mlmanager.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myrias.mlmanager.auth.AppUser;
import com.myrias.mlmanager.meli.MeliAuthService;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AiAssistantServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OpenRouterClient openRouter;
    private AiModelSettingsService modelSettings;
    private AiToolRegistry tools;
    private PendingActionStore pendingActions;
    private MeliAuthService meliAuth;
    private AiCustomizationService customization;
    private AiAuditService audit;
    private AiQuotaService quota;
    private AiAssistantService service;
    private AppUser user;

    @BeforeEach
    void setUp() {
        openRouter = mock(OpenRouterClient.class);
        modelSettings = mock(AiModelSettingsService.class);
        tools = mock(AiToolRegistry.class);
        pendingActions = new PendingActionStore(Duration.ofMinutes(10));
        meliAuth = mock(MeliAuthService.class);
        customization = mock(AiCustomizationService.class);
        when(customization.promptContext()).thenReturn("");
        // Auditoria real com repositório mockado: o Tracker é package-private.
        audit = new AiAuditService(mock(AiCommandLogRepository.class));
        quota = mock(AiQuotaService.class);
        service = new AiAssistantService(openRouter, modelSettings, tools, pendingActions,
                meliAuth, customization, audit, quota, 1500, 8);

        user = mock(AppUser.class);
        when(user.getId()).thenReturn(1L);
        when(meliAuth.listAccounts()).thenReturn(List.of());
        when(modelSettings.currentModel()).thenReturn("m");
        // Tools oferecidas ao usuário — o loop só despacha nomes desta lista.
        when(tools.toolDefinitions(user)).thenReturn((ArrayNode) json("""
            [{"type":"function","function":{"name":"list_accounts"}},
             {"type":"function","function":{"name":"bulk_update_items"}}]
            """));
    }

    private static JsonNode json(String s) {
        try { return MAPPER.readTree(s); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static final JsonNode FINAL_RESP = json(
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"resposta final\"}}]}");

    private static final JsonNode READ_TOOL_RESP = json("""
        {"choices":[{"message":{"role":"assistant","content":null,
          "tool_calls":[{"id":"c1","type":"function","function":{"name":"list_accounts","arguments":"{}"}}]}}]}
        """);

    private static final JsonNode WRITE_TOOL_RESP = json("""
        {"choices":[{"message":{"role":"assistant","content":"Vou pausar o anúncio.",
          "tool_calls":[{"id":"c2","type":"function","function":{"name":"bulk_update_items",
            "arguments":"{\\"groups\\":[{\\"user_id\\":1,\\"item_ids\\":[\\"MLB1\\"]}],\\"updates\\":{\\"status\\":\\"paused\\"}}"}}]}}]}
        """);

    private JsonNode userMessages() {
        return json("[{\"role\":\"user\",\"content\":\"oi\"}]");
    }

    @Test
    void respostaSemToolsVoltaDireto() {
        when(openRouter.chat(any())).thenReturn(FINAL_RESP);
        Map<String, Object> out = service.chat(user, userMessages(), "user:1");
        assertEquals("resposta final", out.get("reply"));
        assertNull(out.get("pending_action"));
    }

    @Test
    void toolDeLeituraExecutaEReChama() {
        when(tools.isWriteTool("list_accounts")).thenReturn(false);
        when(tools.executeRead(eq("list_accounts"), any())).thenReturn("[]");
        when(openRouter.chat(any())).thenReturn(READ_TOOL_RESP, FINAL_RESP);

        Map<String, Object> out = service.chat(user, userMessages(), "user:1");

        assertEquals("resposta final", out.get("reply"));
        verify(tools).executeRead(eq("list_accounts"), any());
        verify(openRouter, times(2)).chat(any());
        @SuppressWarnings("unchecked")
        List<String> events = (List<String>) out.get("tool_events");
        assertEquals(1, events.size());
    }

    @Test
    void toolDeEscritaViraPendingActionEPara() {
        when(tools.isWriteTool("bulk_update_items")).thenReturn(true);
        when(tools.summarize(eq("bulk_update_items"), any())).thenReturn("Alterar 1 anúncio(s)");
        when(openRouter.chat(any())).thenReturn(WRITE_TOOL_RESP);

        Map<String, Object> out = service.chat(user, userMessages(), "user:1");

        assertEquals("Vou pausar o anúncio.", out.get("reply"));
        @SuppressWarnings("unchecked")
        Map<String, Object> pending = (Map<String, Object>) out.get("pending_action");
        assertNotNull(pending);
        assertEquals("bulk_update_items", pending.get("tool"));
        assertNotNull(pendingActions.consume((String) pending.get("id"), 1L));
        verify(openRouter, times(1)).chat(any());
        verify(tools, never()).executeWrite(any(), any());
    }

    @Test
    void erroDeToolViraToolResultENaoDerruba() {
        when(tools.isWriteTool("list_accounts")).thenReturn(false);
        when(tools.executeRead(eq("list_accounts"), any()))
                .thenThrow(new IllegalStateException("ML fora do ar"));
        when(openRouter.chat(any())).thenReturn(READ_TOOL_RESP, FINAL_RESP);
        Map<String, Object> out = service.chat(user, userMessages(), "user:1");
        assertEquals("resposta final", out.get("reply"));
    }

    @Test
    void limiteDeIteracoesCorta() {
        when(tools.isWriteTool("list_accounts")).thenReturn(false);
        when(tools.executeRead(eq("list_accounts"), any())).thenReturn("[]");
        when(openRouter.chat(any())).thenReturn(READ_TOOL_RESP);
        Map<String, Object> out = service.chat(user, userMessages(), "user:1");
        assertTrue(((String) out.get("reply")).contains("Não consegui concluir"));
        verify(openRouter, times(8)).chat(any());
    }

    // Deviation from the plan: AiToolRegistry.summarize now throws
    // IllegalArgumentException on invalid write args (Task 4 review fix).
    // The write branch must catch it, feed it back as a tool result, and
    // keep looping instead of creating a pending action / crashing chat().
    @Test
    void toolDeEscritaComArgsInvalidosViraToolResultENaoCriaPendingAction() {
        when(tools.isWriteTool("bulk_update_items")).thenReturn(true);
        when(tools.summarize(eq("bulk_update_items"), any()))
                .thenThrow(new IllegalArgumentException("groups vazio ou inválido"));
        when(openRouter.chat(any())).thenReturn(WRITE_TOOL_RESP, FINAL_RESP);

        Map<String, Object> out = service.chat(user, userMessages(), "user:1");

        assertEquals("resposta final", out.get("reply"));
        assertNull(out.get("pending_action"));
        verify(openRouter, times(2)).chat(any());
        verify(tools, never()).executeWrite(any(), any());
    }

    // O ponto do teto de gasto: bloquear ANTES da chamada. Um teto que barra
    // depois gasta exatamente o dinheiro que devia poupar.
    @Test
    void quotaEstouradaNaoChamaOpenRouter() {
        doThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "estourou"))
                .when(quota).require("user:1");

        assertThrows(ResponseStatusException.class,
                () -> service.chat(user, userMessages(), "user:1"));

        verifyNoInteractions(openRouter);
    }

    // Cada iteração do loop debita na hora — senão as 8 voltas de um único
    // comando passariam todas contra o mesmo saldo pré-comando.
    @Test
    void cadaIteracaoDebitaOCustoNoTeto() {
        when(tools.isWriteTool("list_accounts")).thenReturn(false);
        when(tools.executeRead(eq("list_accounts"), any())).thenReturn("[]");
        when(openRouter.chat(any())).thenReturn(
                withCost(READ_TOOL_RESP, "0.03"), withCost(FINAL_RESP, "0.02"));

        service.chat(user, userMessages(), "user:1");

        verify(quota).record("user:1", new BigDecimal("0.03"));
        verify(quota).record("user:1", new BigDecimal("0.02"));
        verify(quota).requireGlobal();   // só a partir da 2ª volta
    }

    private static JsonNode withCost(JsonNode base, String cost) {
        ObjectNode copy = (ObjectNode) base.deepCopy();
        copy.set("usage", MAPPER.createObjectNode().put("cost", cost));
        return copy;
    }

    // O histórico vem do cliente: sem corte, quem chama a API escolhe quantos
    // tokens de prompt nós pagamos. Confere que o payload sai limitado.
    @Test
    void historicoDoClienteEhCortadoNoPayload() {
        when(openRouter.chat(any())).thenReturn(FINAL_RESP);
        ArrayNode longa = MAPPER.createArrayNode();
        for (int i = 0; i < 30; i++) {
            longa.add(MAPPER.createObjectNode().put("role", "user").put("content", "msg " + i));
        }
        longa.add(MAPPER.createObjectNode().put("role", "user")
                .put("content", "x".repeat(10_000)));

        service.chat(user, longa, "user:1");

        ArgumentCaptor<ObjectNode> payload = ArgumentCaptor.forClass(ObjectNode.class);
        verify(openRouter).chat(payload.capture());
        JsonNode messages = payload.getValue().path("messages");
        // 1 system + no máximo 20 do cliente.
        assertEquals(21, messages.size());
        // Manteve a cauda, não o começo.
        assertEquals("x".repeat(4000), messages.get(20).path("content").asText());
        assertEquals("msg 11", messages.get(1).path("content").asText());
        assertEquals(1500, payload.getValue().path("max_tokens").asInt());
    }

    // Defesa: o modelo pode alucinar uma tool que não foi oferecida a este
    // usuário (toolDefinitions filtra por permissão). O loop não pode
    // despachá-la — vira tool result de erro e segue.
    @Test
    void toolNaoOferecidaNaoExecutaEViraToolResult() {
        when(tools.toolDefinitions(user)).thenReturn(MAPPER.createArrayNode());
        when(openRouter.chat(any())).thenReturn(READ_TOOL_RESP, FINAL_RESP);

        Map<String, Object> out = service.chat(user, userMessages(), "user:1");

        assertEquals("resposta final", out.get("reply"));
        assertNull(out.get("pending_action"));
        verify(tools, never()).executeRead(any(), any());
        verify(tools, never()).summarize(any(), any());
        verify(tools, never()).executeWrite(any(), any());
        @SuppressWarnings("unchecked")
        List<String> events = (List<String>) out.get("tool_events");
        assertEquals(1, events.size());
        assertTrue(events.get(0).contains("não disponível"));
    }
}
