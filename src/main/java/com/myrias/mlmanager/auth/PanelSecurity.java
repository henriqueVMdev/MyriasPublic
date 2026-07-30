package com.myrias.mlmanager.auth;

import com.myrias.mlmanager.ops.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Checagem de usuário/permissão do painel a partir do request, espelho de
 * {@code get_current_app_user} + {@code require_permission} do dependencies.py.
 * O {@link AppAuthFilter} já validou o cookie e injetou o id; aqui carregamos
 * o usuário e aplicamos a permissão por rota (admin passa em tudo).
 */
@Component
public class PanelSecurity {

    private final UserAccountService users;

    public PanelSecurity(UserAccountService users) {
        this.users = users;
    }

    /** Usuário logado e ativo, ou 401. */
    public AppUser currentUser(HttpServletRequest request) {
        Object attr = request.getAttribute(AppAuthFilter.USER_ID_ATTR);
        if (!(attr instanceof Long userId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Não autenticado.");
        }
        AppUser user = users.getById(userId);
        if (user == null || !user.isActive()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão inválida.");
        }
        // Registra quem é o ator da requisição para os OperationLogs (mirror do
        // set_current_actor do dependencies.py).
        CurrentActor.set(user.getDisplayName() != null && !user.getDisplayName().isBlank()
                ? user.getDisplayName() : user.getUsername());
        return user;
    }

    /** Exige a chave de permissão (admin passa direto), senão 403. */
    public AppUser require(HttpServletRequest request, String key) {
        AppUser user = currentUser(request);
        if (user.isAdmin() || user.getPermissions().contains(key)) {
            return user;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sem permissão para esta ação.");
    }

    /** Exige usuário admin, senão 403 (espelho de require_admin do dependencies.py). */
    public AppUser requireAdmin(HttpServletRequest request) {
        AppUser user = currentUser(request);
        if (!user.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito a administradores.");
        }
        return user;
    }
}
