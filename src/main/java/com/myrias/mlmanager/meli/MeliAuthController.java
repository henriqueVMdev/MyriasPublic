package com.myrias.mlmanager.meli;

import com.myrias.mlmanager.auth.PanelSecurity;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Rotas OAuth do Mercado Livre. Espelho de backend/app/api/auth.py.
 * status/accounts/events são livres (qualquer sessão); switch/remove/logout
 * exigem a permissão {@code manage_accounts}, igual ao FastAPI.
 */
@RestController
@RequestMapping("/api/auth")
public class MeliAuthController {

    private static final Logger log = LoggerFactory.getLogger(MeliAuthController.class);

    private final MeliAuthService auth;
    private final PanelSecurity security;
    private final String frontendUrl;
    private final boolean demoMode;
    private final SecureRandom random = new SecureRandom();

    public MeliAuthController(MeliAuthService auth, PanelSecurity security,
                              @Value("${app.frontend-url:http://localhost:5173}") String frontendUrl,
                              @Value("${app.demo-mode:false}") boolean demoMode) {
        this.auth = auth;
        this.security = security;
        this.frontendUrl = frontendUrl;
        this.demoMode = demoMode;
    }

    public record AccountRef(Long user_id) {}

    /**
     * Estas duas rotas são GET e estão em OPEN_PATHS, então escapam do bloqueio
     * de mutação do AppAuthFilter por dois motivos — precisam de guarda própria.
     */
    private void refuseInDemo() {
        if (demoMode) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Modo demonstração: não é possível conectar contas do Mercado Livre.");
        }
    }

    /** Redireciona para a autorização do ML. */
    @GetMapping("/login")
    public ResponseEntity<Void> login() {
        refuseInDemo();
        String state = randomState();
        String url = auth.getAuthUrl(state);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }

    /** Recebe o code do OAuth, troca por tokens e volta pro frontend. */
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam String code,
                                         @RequestParam(defaultValue = "") String state) {
        refuseInDemo();
        try {
            auth.exchangeCode(code, state);
        } catch (Exception e) {
            log.error("OAuth callback error: {}", e.getMessage());
            String url = frontendUrl + "?auth_error="
                    + URLEncoder.encode(e.getMessage() == null ? "erro" : e.getMessage(), StandardCharsets.UTF_8);
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(frontendUrl)).build();
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return auth.getStatus(null);
    }

    @GetMapping("/accounts")
    public Map<String, Object> accounts() {
        return Map.of("accounts", auth.listAccounts());
    }

    @PostMapping("/accounts/switch")
    public Map<String, Object> switchAccount(@RequestBody AccountRef body, HttpServletRequest request) {
        security.require(request, "manage_accounts");
        try {
            return auth.setActiveAccount(body.user_id());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conta não encontrada");
        }
    }

    @PostMapping("/accounts/remove")
    public Map<String, Object> removeAccount(@RequestBody AccountRef body, HttpServletRequest request) {
        security.require(request, "manage_accounts");
        auth.removeAccount(body.user_id());
        return Map.of("ok", true);
    }

    @GetMapping("/events")
    public Map<String, Object> events() {
        return Map.of("events", auth.getLastAuthEvents(10));
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        security.require(request, "manage_accounts");
        auth.logout();
        return Map.of("message", "Conta desconectada com sucesso");
    }

    private String randomState() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
