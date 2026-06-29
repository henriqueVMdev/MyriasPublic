package com.hrb.mlmanager.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Porteiro de todas as rotas /api/*. Espelho do AppAuthMiddleware do FastAPI:
 * libera rotas abertas (login/health/webhooks), valida o cookie assinado e
 * injeta o userId; durante o bootstrap (sem nenhum usuário) deixa passar.
 */
@Component
public class AppAuthFilter extends OncePerRequestFilter {

    public static final String USER_ID_ATTR = "appUserId";

    private static final Set<String> OPEN_PATHS = Set.of(
            "/api/health",
            "/api/app/login",
            "/api/app/session",
            "/api/app/users",
            "/api/webhooks/ml",
            "/api/auth/login",
            "/api/auth/callback"
    );

    private final SessionTokenService tokens;
    private final UserAccountService users;
    private final ObjectMapper mapper = new ObjectMapper();

    public AppAuthFilter(SessionTokenService tokens, UserAccountService users) {
        this.tokens = tokens;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();

        // Rotas abertas e tudo fora de /api/ passam direto.
        if (OPEN_PATHS.contains(path) || !path.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }

        Long userId = tokens.verify(readCookie(request)).orElse(null);
        if (userId != null) {
            request.setAttribute(USER_ID_ATTR, userId);
            chain.doFilter(request, response);
            return;
        }

        // Sem sessão válida: libera só enquanto não existe nenhum usuário (bootstrap).
        if (users.count() == 0) {
            chain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        mapper.writeValue(response.getWriter(), Map.of("detail", "Não autenticado"));
    }

    private String readCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (var c : request.getCookies()) {
            if (SessionTokenService.COOKIE_NAME.equals(c.getName())) return c.getValue();
        }
        return null;
    }
}
