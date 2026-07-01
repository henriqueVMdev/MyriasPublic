package com.hrb.mlmanager.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * CRUD de usuários do painel — restrito a administradores. Espelho de
 * backend/app/api/users.py.
 *
 * O catálogo de permissões (/permissions) fica liberado a qualquer sessão (o
 * frontend monta os checkboxes com ele); o resto exige admin. A lista pública
 * pro dropdown de login e a sessão ficam no {@link AuthController} (/api/app/*).
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private static final int USERNAME_MIN = 3, USERNAME_MAX = 60;
    private static final int PASSWORD_MIN = 4, PASSWORD_MAX = 128;
    private static final int DISPLAY_NAME_MAX = 120;

    private final UserAccountService users;
    private final PanelSecurity security;

    public UserController(UserAccountService users, PanelSecurity security) {
        this.users = users;
        this.security = security;
    }

    public record UserCreate(String username, String password, String display_name,
                             Boolean is_admin, List<String> permissions) {}

    public record UserUpdate(String display_name, String password,
                             Boolean is_admin, Boolean is_active, List<String> permissions) {}

    /** Catálogo de chaves disponíveis (pro frontend montar os checkboxes). */
    @GetMapping("/permissions")
    public Map<String, Object> permissionCatalog(HttpServletRequest request) {
        security.currentUser(request); // qualquer sessão válida
        return Map.of(
                "sections", Permissions.SECTIONS,
                "actions", Permissions.ACTIONS,
                "metrics", Permissions.METRICS);
    }

    @GetMapping("")
    public List<UserOut> listUsers(HttpServletRequest request) {
        security.requireAdmin(request);
        return users.listAll().stream().map(UserOut::of).toList();
    }

    @PostMapping("")
    @ResponseStatus(HttpStatus.CREATED)
    public UserOut createUser(@RequestBody UserCreate body, HttpServletRequest request) {
        security.requireAdmin(request);

        String username = body.username() == null ? "" : body.username().strip();
        if (username.length() < USERNAME_MIN || username.length() > USERNAME_MAX) {
            throw unprocessable("username deve ter entre " + USERNAME_MIN + " e " + USERNAME_MAX + " caracteres.");
        }
        validatePassword(body.password(), true);
        validateDisplayName(body.display_name());
        if (users.getByUsername(username) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe um usuário com esse username.");
        }

        AppUser user = users.create(username, body.password(), body.display_name(),
                Boolean.TRUE.equals(body.is_admin()), body.permissions());
        return UserOut.of(user);
    }

    @PutMapping("/{userId}")
    public UserOut updateUser(@PathVariable Long userId, @RequestBody UserUpdate body,
                              HttpServletRequest request) {
        AppUser admin = security.requireAdmin(request);
        AppUser user = users.getById(userId);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado.");
        }

        if (body.display_name() != null) {
            validateDisplayName(body.display_name());
            String dn = body.display_name().strip();
            user.setDisplayName(dn.isEmpty() ? null : dn);
        }
        if (body.password() != null && !body.password().isEmpty()) {
            validatePassword(body.password(), false);
            user.setHashedPassword(users.hashPassword(body.password()));
        }
        if (body.permissions() != null) {
            user.setPermissions(Permissions.valid(body.permissions()));
        }
        if (body.is_admin() != null) {
            // Não permitir o admin remover o próprio status (evita auto-lockout).
            if (admin.getId().equals(user.getId()) && !body.is_admin()) {
                throw badRequest("Você não pode remover seu próprio acesso de admin.");
            }
            user.setAdmin(body.is_admin());
        }
        if (body.is_active() != null) {
            if (admin.getId().equals(user.getId()) && !body.is_active()) {
                throw badRequest("Você não pode desativar a si mesmo.");
            }
            user.setActive(body.is_active());
        }

        return UserOut.of(users.save(user));
    }

    @DeleteMapping("/{userId}")
    public Map<String, Object> deleteUser(@PathVariable Long userId, HttpServletRequest request) {
        AppUser admin = security.requireAdmin(request);
        if (admin.getId().equals(userId)) {
            throw badRequest("Você não pode excluir a si mesmo.");
        }
        AppUser user = users.getById(userId);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado.");
        }
        users.delete(user);
        return Map.of("ok", true);
    }

    // ---- validações (espelham os Field(...) do pydantic) --------------------

    private void validatePassword(String password, boolean required) {
        if (password == null || password.isEmpty()) {
            if (required) throw unprocessable("password é obrigatório.");
            return;
        }
        if (password.length() < PASSWORD_MIN || password.length() > PASSWORD_MAX) {
            throw unprocessable("password deve ter entre " + PASSWORD_MIN + " e " + PASSWORD_MAX + " caracteres.");
        }
    }

    private void validateDisplayName(String displayName) {
        if (displayName != null && displayName.length() > DISPLAY_NAME_MAX) {
            throw unprocessable("display_name limitado a " + DISPLAY_NAME_MAX + " caracteres.");
        }
    }

    private static ResponseStatusException unprocessable(String msg) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, msg);
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
