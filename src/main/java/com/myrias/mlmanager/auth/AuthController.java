package com.hrb.mlmanager.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas de login do painel. Espelho dos handlers /api/app/* do FastAPI (main.py).
 */
@RestController
@RequestMapping("/api/app")
public class AuthController {

    private static final int LOGIN_MAX_ATTEMPTS = 5;
    private static final long LOGIN_WINDOW_SECONDS = 300; // 5 min

    private final UserAccountService users;
    private final SessionTokenService tokens;
    private final boolean devProfile;
    private final boolean demoMode;

    // ip -> timestamps de tentativas falhas. ponytail: in-memory, serve pra
    // 1 instância; se escalar pra várias réplicas, trocar por Redis.
    private final Map<String, List<Long>> loginAttempts = new ConcurrentHashMap<>();

    public AuthController(UserAccountService users, SessionTokenService tokens, Environment env,
                          @Value("${app.demo-mode:false}") boolean demoMode) {
        this.users = users;
        this.tokens = tokens;
        this.devProfile = env.acceptsProfiles(Profiles.of("dev"));
        this.demoMode = demoMode;
    }

    public record LoginBody(String username, String password) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginBody body, HttpServletRequest request) {
        String ip = clientIp(request);
        if (!rateLimitOk(ip)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("detail", "Muitas tentativas. Aguarde 5 minutos."));
        }

        AppUser user = users.authenticate(body.username(), body.password());
        if (user != null) {
            ResponseCookie cookie = ResponseCookie.from(SessionTokenService.COOKIE_NAME, tokens.make(user.getId()))
                    .httpOnly(true)
                    .secure(request.isSecure())
                    .sameSite("Lax")
                    .path("/")
                    .maxAge(Duration.ofDays(30))   // casa com o TTL do SessionTokenService
                    .build();
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, cookie.toString())
                    .body(Map.of("ok", true, "user", UserDto.of(user)));
        }

        recordFailure(ip);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("detail", "Usuário ou senha incorretos"));
    }

    @GetMapping("/session")
    public Map<String, Object> session(HttpServletRequest request) {
        // demo_mode vai nos três caminhos: é por aqui que o SPA descobre que
        // precisa mostrar o banner e desabilitar os botões de escrita.
        Map<String, Object> out = new java.util.HashMap<>();
        out.put("demo_mode", demoMode);
        Long userId = tokens.verify(readCookie(request)).orElse(null);
        if (userId != null) {
            AppUser user = users.getById(userId);
            if (user != null && user.isActive()) {
                out.put("authenticated", true);
                out.put("user", UserDto.of(user));
                return out;
            }
        }
        // Bootstrap: sem usuários ainda → painel liberado até criar o admin.
        // Só no dev, espelhando o AppAuthFilter: em prod o admin já existe.
        if (devProfile && users.count() == 0) {
            out.put("authenticated", true);
            out.put("password_required", false);
            out.put("user", null);
            return out;
        }
        out.put("authenticated", false);
        out.put("user", null);
        return out;
    }

    /** Lista pública (só username + nome) pro dropdown da tela de login. */
    @GetMapping("/users")
    public List<Map<String, String>> publicUsers() {
        List<Map<String, String>> out = new ArrayList<>();
        for (AppUser u : users.listActivePublic()) {
            Map<String, String> m = new java.util.HashMap<>();
            m.put("username", u.getUsername());
            m.put("display_name", u.getDisplayName());
            out.add(m);
        }
        return out;
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        ResponseCookie cleared = ResponseCookie.from(SessionTokenService.COOKIE_NAME, "")
                .httpOnly(true).path("/").maxAge(0).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cleared.toString())
                .body(Map.of("ok", true));
    }

    private String readCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (var c : request.getCookies()) {
            if (SessionTokenService.COOKIE_NAME.equals(c.getName())) return c.getValue();
        }
        return null;
    }

    private static String clientIp(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        return ip == null ? "unknown" : ip;
    }

    private boolean rateLimitOk(String ip) {
        long now = System.currentTimeMillis() / 1000;
        List<Long> attempts = loginAttempts.getOrDefault(ip, List.of());
        long recent = attempts.stream().filter(t -> now - t < LOGIN_WINDOW_SECONDS).count();
        return recent < LOGIN_MAX_ATTEMPTS;
    }

    private void recordFailure(String ip) {
        long now = System.currentTimeMillis() / 1000;
        loginAttempts.compute(ip, (k, list) -> {
            List<Long> next = list == null ? new ArrayList<>() : new ArrayList<>(list);
            next.removeIf(t -> now - t >= LOGIN_WINDOW_SECONDS);
            next.add(now);
            return next;
        });
    }
}
