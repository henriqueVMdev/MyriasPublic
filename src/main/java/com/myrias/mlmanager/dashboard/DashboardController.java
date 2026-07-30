package com.myrias.mlmanager.dashboard;

import com.myrias.mlmanager.auth.PanelSecurity;
import com.myrias.mlmanager.dashboard.DashboardService.Range;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Métricas do dashboard. Espelho de backend/app/api/dashboard.py.
 *
 * Só {@code /revenue} exige a métrica {@code dashboard_revenue} (dado financeiro);
 * os demais precisam apenas de uma sessão válida do painel, garantida pelo
 * {@link com.myrias.mlmanager.auth.AppAuthFilter}. O endpoint /debug-performance do
 * Python era de diagnóstico e não é consumido pelo frontend — omitido.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService service;
    private final PanelSecurity security;

    public DashboardController(DashboardService service, PanelSecurity security) {
        this.service = service;
        this.security = security;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(
            @RequestParam(defaultValue = "all") String accounts,
            HttpServletRequest request) {
        security.currentUser(request);
        return service.summary(accounts);
    }

    @GetMapping("/revenue")
    public Map<String, Object> revenue(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            HttpServletRequest request) {
        security.require(request, "dashboard_revenue");
        Range range = service.resolveRange(clampDays(days), from, to);
        if (range == null) return Map.of("error", "from/to devem ser YYYY-MM-DD");
        return service.revenue(range);
    }

    @GetMapping("/performance")
    public Map<String, Object> performance(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            HttpServletRequest request) {
        security.currentUser(request);
        Range range = service.resolveRange(clampDays(days), from, to);
        if (range == null) return Map.of("error", "from/to devem ser YYYY-MM-DD");
        return service.performance(range);
    }

    @GetMapping("/sales")
    public Map<String, Object> sales(
            @RequestParam(defaultValue = "1") int days,
            HttpServletRequest request) {
        security.currentUser(request);
        return service.sales(clampDays(days));
    }

    @GetMapping("/reputation")
    public Map<String, Object> reputation(HttpServletRequest request) {
        security.currentUser(request);
        return service.reputation();
    }

    // days em [1, 90], como os Query(ge=1, le=90) do FastAPI.
    private static int clampDays(int days) {
        return Math.max(1, Math.min(days, 90));
    }
}
