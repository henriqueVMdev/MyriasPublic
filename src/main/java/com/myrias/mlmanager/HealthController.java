package com.hrb.mlmanager;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Probe de saúde. Espelho do @app.get("/api/health") do main.py — o
 * AppAuthFilter já libera o caminho, mas faltava o handler. */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
