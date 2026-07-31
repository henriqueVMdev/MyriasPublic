package com.myrias.mlmanager.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Visão do usuário do painel — serve tanto o CRUD de admin quanto a sessão/login.
 * Shape em snake_case, igual ao que o frontend (api/users.ts, interface AppUser)
 * consome: {@code display_name}, {@code is_admin}, {@code is_active}.
 *
 * Os @JsonProperty são obrigatórios: sem eles o Jackson mandaria camelCase e o
 * stores/auth.ts, que lê {@code is_admin}, não detectaria o admin na sessão.
 */
public record UserOut(
        Long id,
        String username,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("is_admin") boolean isAdmin,
        @JsonProperty("is_active") boolean isActive,
        List<String> permissions) {

    public static UserOut of(AppUser u) {
        return new UserOut(u.getId(), u.getUsername(), u.getDisplayName(),
                u.isAdmin(), u.isActive(), u.getPermissions());
    }
}
