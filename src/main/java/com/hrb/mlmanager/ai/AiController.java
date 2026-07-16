package com.hrb.mlmanager.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.hrb.mlmanager.auth.AppUser;
import com.hrb.mlmanager.auth.PanelSecurity;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Rotas do assistente de IA. Chat livre pra quem tem `assistente`; a execução
 *  de escrita revalida a permissão real da ação (bulk_edit/manage_promotions). */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiAssistantService assistant;
    private final AiToolRegistry tools;
    private final PendingActionStore pendingActions;
    private final PanelSecurity security;
    private final AiModelSettingsService modelSettings;

    public AiController(AiAssistantService assistant, AiToolRegistry tools,
                        PendingActionStore pendingActions, PanelSecurity security,
                        AiModelSettingsService modelSettings) {
        this.assistant = assistant;
        this.tools = tools;
        this.pendingActions = pendingActions;
        this.security = security;
        this.modelSettings = modelSettings;
    }

    public record ModelUpdate(String model) {}

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody JsonNode body, HttpServletRequest request) {
        AppUser user = security.require(request, "assistente");
        JsonNode messages = body.path("messages");
        if (!messages.isArray() || messages.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "messages nao pode ser vazio");
        }
        try {
            return assistant.chat(user, messages);
        } catch (OpenRouterException e) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("reply", "Assistente indisponível: " + e.getMessage());
            out.put("tool_events", List.of());
            out.put("pending_action", null);
            out.put("error", true);
            return out;
        }
    }

    @PostMapping("/actions/{id}/confirm")
    public Map<String, Object> confirm(@PathVariable String id, HttpServletRequest request) {
        AppUser user = security.require(request, "assistente");
        var action = pendingActions.consume(id, user.getId());
        if (action == null) {
            throw new ResponseStatusException(HttpStatus.GONE,
                    "Ação expirou ou não existe. Peça de novo ao assistente.");
        }
        String required = tools.writePermission(action.tool());
        if (!user.isAdmin() && !user.getPermissions().contains(required)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Sem permissão para esta ação (" + required + ").");
        }
        Map<String, Object> result = tools.executeWrite(action.tool(), action.args());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("summary", action.summary());
        out.put("result", result);
        return out;
    }

    @PostMapping("/actions/{id}/reject")
    public Map<String, Object> reject(@PathVariable String id, HttpServletRequest request) {
        AppUser user = security.require(request, "assistente");
        pendingActions.discard(id, user.getId());
        return Map.of("ok", true);
    }

    @GetMapping("/models")
    public Map<String, Object> models(HttpServletRequest request) {
        security.requireAdmin(request);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("models", modelSettings.availableModels());
        out.put("selected", modelSettings.currentModel());
        return out;
    }

    @PutMapping("/model")
    public Map<String, Object> updateModel(@RequestBody ModelUpdate body, HttpServletRequest request) {
        security.requireAdmin(request);
        try {
            return Map.of("selected", modelSettings.updateModel(body == null ? null : body.model()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }
    }
}
