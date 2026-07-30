package com.hrb.mlmanager.ai;

import com.hrb.mlmanager.auth.PanelSecurity;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Administração central do agente, restrita a usuários administradores. */
@RestController
@RequestMapping("/api/ai/admin")
public class AdminAiController {

    private final PanelSecurity security;
    private final AiModelSettingsService models;
    private final AiAuditService audit;
    private final AiCustomizationService customization;
    private final AiToolRegistry tools;

    public AdminAiController(PanelSecurity security, AiModelSettingsService models,
                             AiAuditService audit, AiCustomizationService customization,
                             AiToolRegistry tools) {
        this.security = security;
        this.models = models;
        this.audit = audit;
        this.customization = customization;
        this.tools = tools;
    }

    public record ModelUpdate(String model) {}
    public record MemoryInput(String title, String content, Boolean enabled) {}
    public record SkillInput(String name, String description, String instructions, Boolean enabled) {}

    @GetMapping("/overview")
    public Map<String, Object> overview(HttpServletRequest request) {
        security.requireAdmin(request);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agent", Map.of(
                "name", "Assistente ML Manager",
                "description", "Agente operacional com acesso controlado aos dados do Mercado Livre.",
                "selected_model", models.currentModel(),
                "max_iterations", 8));
        out.put("catalog_count", models.allModels().size());
        out.put("memories_count", customization.listMemories().size());
        out.put("skills_count", customization.listSkills().size());
        out.put("tools", tools.adminCatalog());
        out.put("usage", audit.stats());
        return out;
    }

    @GetMapping("/models")
    public Map<String, Object> catalog(HttpServletRequest request) {
        security.requireAdmin(request);
        return Map.of("models", models.allModels(), "selected", models.currentModel());
    }

    @PostMapping("/models/refresh")
    public Map<String, Object> refreshCatalog(HttpServletRequest request) {
        security.requireAdmin(request);
        return Map.of("models", models.refreshModels(), "selected", models.currentModel());
    }

    @PutMapping("/model")
    public Map<String, Object> updateModel(@RequestBody ModelUpdate body,
                                            HttpServletRequest request) {
        security.requireAdmin(request);
        try {
            return Map.of("selected", models.updateModel(body == null ? null : body.model()));
        } catch (IllegalArgumentException e) {
            throw invalid(e);
        }
    }

    @GetMapping("/commands")
    public Map<String, Object> commands(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest request) {
        security.requireAdmin(request);
        return audit.listCommands(userId, status, query, offset, limit);
    }

    @GetMapping("/commands/stats")
    public Map<String, Object> commandStats(HttpServletRequest request) {
        security.requireAdmin(request);
        return audit.stats();
    }

    @GetMapping("/memories")
    public Map<String, Object> memories(HttpServletRequest request) {
        security.requireAdmin(request);
        return Map.of("memories", customization.listMemories());
    }

    @PostMapping("/memories")
    public AiMemory createMemory(@RequestBody MemoryInput body, HttpServletRequest request) {
        security.requireAdmin(request);
        try {
            return customization.createMemory(body == null ? null : body.title(),
                    body == null ? null : body.content(), body == null || body.enabled() == null
                            || body.enabled());
        } catch (IllegalArgumentException e) {
            throw invalid(e);
        }
    }

    @PutMapping("/memories/{id}")
    public AiMemory updateMemory(@PathVariable long id, @RequestBody MemoryInput body,
                                  HttpServletRequest request) {
        security.requireAdmin(request);
        try {
            return customization.updateMemory(id, body == null ? null : body.title(),
                    body == null ? null : body.content(), body != null
                            && Boolean.TRUE.equals(body.enabled()));
        } catch (IllegalArgumentException e) {
            throw invalid(e);
        }
    }

    @DeleteMapping("/memories/{id}")
    public Map<String, Object> deleteMemory(@PathVariable long id, HttpServletRequest request) {
        security.requireAdmin(request);
        try {
            customization.deleteMemory(id);
            return Map.of("ok", true);
        } catch (IllegalArgumentException e) {
            throw invalid(e);
        }
    }

    @GetMapping("/skills")
    public Map<String, Object> skills(HttpServletRequest request) {
        security.requireAdmin(request);
        return Map.of("skills", customization.listSkills());
    }

    @PostMapping("/skills")
    public AiSkill createSkill(@RequestBody SkillInput body, HttpServletRequest request) {
        security.requireAdmin(request);
        try {
            return customization.createSkill(body == null ? null : body.name(),
                    body == null ? null : body.description(),
                    body == null ? null : body.instructions(),
                    body == null || body.enabled() == null || body.enabled());
        } catch (IllegalArgumentException e) {
            throw invalid(e);
        }
    }

    @PutMapping("/skills/{id}")
    public AiSkill updateSkill(@PathVariable long id, @RequestBody SkillInput body,
                                HttpServletRequest request) {
        security.requireAdmin(request);
        try {
            return customization.updateSkill(id, body == null ? null : body.name(),
                    body == null ? null : body.description(),
                    body == null ? null : body.instructions(),
                    body != null && Boolean.TRUE.equals(body.enabled()));
        } catch (IllegalArgumentException e) {
            throw invalid(e);
        }
    }

    @DeleteMapping("/skills/{id}")
    public Map<String, Object> deleteSkill(@PathVariable long id, HttpServletRequest request) {
        security.requireAdmin(request);
        try {
            customization.deleteSkill(id);
            return Map.of("ok", true);
        } catch (IllegalArgumentException e) {
            throw invalid(e);
        }
    }

    private static ResponseStatusException invalid(IllegalArgumentException e) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }
}
