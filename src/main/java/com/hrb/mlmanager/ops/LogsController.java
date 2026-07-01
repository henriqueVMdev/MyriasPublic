package com.hrb.mlmanager.ops;

import com.hrb.mlmanager.auth.PanelSecurity;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas do histórico de operações. Espelho de backend/app/api/logs.py.
 *
 * Permissões (iguais ao FastAPI):
 * - lista/operations/by-item → seção {@code logs}
 * - atendimento → seção {@code atendimento_historico}
 * - actors/stats → qualquer sessão válida (sem chave específica)
 * A sessão do painel já é exigida pelo {@link com.hrb.mlmanager.auth.AppAuthFilter}.
 */
@RestController
@RequestMapping("/api/logs")
public class LogsController {

    private final OperationLogService service;
    private final PanelSecurity security;

    public LogsController(OperationLogService service, PanelSecurity security) {
        this.service = service;
        this.security = security;
    }

    @GetMapping("")
    public Map<String, Object> listLogs(
            @RequestParam(required = false) String operation_type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String item_id,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest request) {
        security.require(request, "logs");
        return service.listLogs(operation_type, status, item_id, safeOffset(offset), safeLimit(limit));
    }

    @GetMapping("/actors")
    public Map<String, Object> actors(HttpServletRequest request) {
        security.currentUser(request); // só exige sessão válida
        return service.listActors();
    }

    @GetMapping("/operations")
    public Map<String, Object> operations(
            @RequestParam(required = false) String operation_type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String date_from,
            @RequestParam(required = false) String date_to,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest request) {
        security.require(request, "logs");
        return service.listOperations(operation_type, status, actor, date_from, date_to,
                safeOffset(offset), safeLimit(limit));
    }

    @GetMapping("/operations/{key}")
    public Map<String, Object> operationDetail(@PathVariable String key, HttpServletRequest request) {
        security.require(request, "logs");
        return service.operationDetail(key);
    }

    @GetMapping("/atendimento")
    public Map<String, Object> atendimento(
            @RequestParam(required = false) String operation_type,
            @RequestParam(required = false) String actor,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest request) {
        security.require(request, "atendimento_historico");
        return service.listAtendimento(operation_type, actor, safeOffset(offset), safeLimit(limit));
    }

    @GetMapping("/by-item")
    public Map<String, Object> byItem(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest request) {
        security.require(request, "logs");
        return service.logsByItem(safeOffset(offset), safeLimit(limit));
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(HttpServletRequest request) {
        security.currentUser(request); // só exige sessão válida
        return service.stats();
    }

    // offset >= 0; limit em [1, 100] (mesmos limites dos Query do FastAPI).
    private static int safeOffset(int offset) {
        return Math.max(0, offset);
    }

    private static int safeLimit(int limit) {
        return Math.max(1, Math.min(limit, 100));
    }
}
