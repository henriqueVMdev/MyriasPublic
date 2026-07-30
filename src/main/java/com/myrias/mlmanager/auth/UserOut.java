package com.myrias.mlmanager.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Visão completa do usuário para o CRUD de admin. Espelho do UserOut (pydantic)
 * do FastAPI: mesmo shape em snake_case que o frontend (api/users.ts) consome —
 * {@code display_name}, {@code is_admin}, {@code is_active}.
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
