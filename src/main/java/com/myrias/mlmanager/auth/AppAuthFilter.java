package com.myrias.mlmanager.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myrias.mlmanager.ops.CurrentActor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
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

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    /** POSTs que são leitura/sessão e precisam continuar funcionando na demo. */
    private static final Set<String> DEMO_ALLOWED_WRITES = Set.of(
            "/api/ai/chat",       // é POST mas não escreve — sem isso a demo não funciona
            "/api/app/login",
            "/api/app/logout");

    private final SessionTokenService tokens;
    private final UserAccountService users;
    private final boolean devProfile;
    private final boolean demoMode;
    private final ObjectMapper mapper = new ObjectMapper();

    public AppAuthFilter(SessionTokenService tokens, UserAccountService users, Environment env,
                         @Value("${app.demo-mode:false}") boolean demoMode) {
        this.tokens = tokens;
        this.users = users;
        this.devProfile = env.acceptsProfiles(Profiles.of("dev"));
        this.demoMode = demoMode;
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

            // Demo: barra qualquer mutação num só lugar. São ~48 handlers de
            // escrita em 16 controllers — guardar cada um seria esquecer algum.
            if (demoMode && !SAFE_METHODS.contains(request.getMethod())
                    && !DEMO_ALLOWED_WRITES.contains(path)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                mapper.writeValue(response.getWriter(),
                        Map.of("detail", "Modo demonstração: alterações estão desabilitadas."));
                return;
            }

            Long userId = tokens.verify(SessionTokenService.readCookie(request)).orElse(null);
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
}
