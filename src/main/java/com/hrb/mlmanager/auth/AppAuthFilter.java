package com.hrb.mlmanager.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrb.mlmanager.ops.CurrentActor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
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
    private final boolean devProfile;
    private final ObjectMapper mapper = new ObjectMapper();

    public AppAuthFilter(SessionTokenService tokens, UserAccountService users, Environment env) {
        this.tokens = tokens;
        this.users = users;
        this.devProfile = env.acceptsProfiles(Profiles.of("dev"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        try {
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

            // Bootstrap (banco sem nenhum usuário) libera TODO /api/* sem sessão —
            // inclusive POST /api/users e /api/ai/chat. Num deploy público isso é o
            // suficiente pra um estranho virar admin, então vale só no dev; em prod
            // o primeiro admin é criado por script/seeder antes de subir.
            if (devProfile && users.count() == 0) {
                chain.doFilter(request, response);
                return;
            }

            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            mapper.writeValue(response.getWriter(), Map.of("detail", "Não autenticado"));
        } finally {
            // Thread vai voltar pro pool — não deixar o ator vazar pro próximo request.
            CurrentActor.clear();
        }
    }

    private String readCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (var c : request.getCookies()) {
            if (SessionTokenService.COOKIE_NAME.equals(c.getName())) return c.getValue();
        }
        return null;
    }
}
