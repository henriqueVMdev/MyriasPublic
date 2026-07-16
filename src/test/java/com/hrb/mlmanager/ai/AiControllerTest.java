package com.hrb.mlmanager.ai;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrb.mlmanager.auth.AppUser;
import com.hrb.mlmanager.auth.PanelSecurity;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class AiControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AiAssistantService assistant;
    private AiToolRegistry tools;
    private PendingActionStore pendingActions;
    private PanelSecurity security;
    private AiModelSettingsService modelSettings;
    private AppUser user;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        assistant = mock(AiAssistantService.class);
        tools = mock(AiToolRegistry.class);
        pendingActions = new PendingActionStore(Duration.ofMinutes(10));
        security = mock(PanelSecurity.class);
        modelSettings = mock(AiModelSettingsService.class);

        user = mock(AppUser.class);
        when(user.getId()).thenReturn(1L);
        when(user.isAdmin()).thenReturn(false);
        when(user.getPermissions()).thenReturn(List.of("assistente")); // SEM bulk_edit
        when(security.require(any(), eq("assistente"))).thenReturn(user);

        mvc = MockMvcBuilders.standaloneSetup(
                new AiController(assistant, tools, pendingActions, security, modelSettings)).build();
    }

    @Test
    void chatSemMessagesDa422() throws Exception {
        mvc.perform(post("/api/ai/chat").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void chatDevolveRespostaDoService() throws Exception {
        when(assistant.chat(eq(user), any()))
                .thenReturn(Map.of("reply", "oi", "tool_events", List.of()));
        mvc.perform(post("/api/ai/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"oi\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("oi"));
    }

    @Test
    void erroDoOpenRouterVira200ComErrorTrue() throws Exception {
        when(assistant.chat(eq(user), any()))
                .thenThrow(new OpenRouterException("Sem créditos"));
        mvc.perform(post("/api/ai/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"oi\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(true))
                .andExpect(jsonPath("$.reply").value("Assistente indisponível: Sem créditos"));
    }

    @Test
    void confirmDeAcaoInexistenteDa410() throws Exception {
        mvc.perform(post("/api/ai/actions/nao-existe/confirm"))
                .andExpect(status().isGone());
    }

    @Test
    void confirmSemPermissaoDaAcaoDa403() throws Exception {
        var action = pendingActions.create(1L, "bulk_update_items", MAPPER.createObjectNode(), "x");
        when(tools.writePermission("bulk_update_items")).thenReturn("bulk_edit");
        mvc.perform(post("/api/ai/actions/" + action.id() + "/confirm"))
                .andExpect(status().isForbidden());
        verify(tools, never()).executeWrite(any(), any());
    }

    @Test
    void confirmComPermissaoExecuta() throws Exception {
        when(user.getPermissions()).thenReturn(List.of("assistente", "bulk_edit"));
        var action = pendingActions.create(1L, "bulk_update_items", MAPPER.createObjectNode(), "Alterar 1");
        when(tools.writePermission("bulk_update_items")).thenReturn("bulk_edit");
        when(tools.executeWrite(eq("bulk_update_items"), any()))
                .thenReturn(Map.of("total", 1, "success", 1));
        mvc.perform(post("/api/ai/actions/" + action.id() + "/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.result.success").value(1));
    }

    @Test
    void modelsListaConfiguracaoGlobalParaAdmin() throws Exception {
        when(modelSettings.availableModels()).thenReturn(List.of("a", "b"));
        when(modelSettings.currentModel()).thenReturn("b");
        mvc.perform(get("/api/ai/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models[0]").value("a"))
                .andExpect(jsonPath("$.selected").value("b"));
        verify(security).requireAdmin(any());
    }

    @Test
    void adminPodeAlterarModeloGlobal() throws Exception {
        when(modelSettings.updateModel("b")).thenReturn("b");
        mvc.perform(put("/api/ai/model")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selected").value("b"));
        verify(security).requireAdmin(any());
        verify(modelSettings).updateModel("b");
    }

    @Test
    void modeloInvalidoDa422() throws Exception {
        when(modelSettings.updateModel("x"))
                .thenThrow(new IllegalArgumentException("Modelo invalido"));
        mvc.perform(put("/api/ai/model")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"x\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void usuarioComumNaoPodeConsultarNemAlterarModelos() throws Exception {
        when(security.requireAdmin(any()))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Somente admin"));

        mvc.perform(get("/api/ai/models"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/ai/model")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"b\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(modelSettings);
    }

    @Test
    void modeloEnviadoManualmenteNoChatNaoEUsadoPeloController() throws Exception {
        when(assistant.chat(eq(user), any()))
                .thenReturn(Map.of("reply", "oi", "tool_events", List.of()));
        mvc.perform(post("/api/ai/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model":"modelo/forjado",
                                 "messages":[{"role":"user","content":"oi"}]}
                                """))
                .andExpect(status().isOk());
        verify(assistant).chat(eq(user), any());
    }
}
