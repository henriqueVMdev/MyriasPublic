package com.hrb.mlmanager.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Visão pública do usuário, mesmo shape do _user_public() do FastAPI:
 * {@code {id, username, display_name, is_admin, permissions}}.
 *
 * Os campos multi-palavra precisam de @JsonProperty em snake_case — sem isso o
 * Jackson serializaria {@code displayName}/{@code isAdmin} (camelCase) e o
 * frontend (stores/auth.ts lê {@code is_admin}) não detectaria o admin na sessão.
 */
public record UserDto(
        Long id,
        String username,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("is_admin") boolean isAdmin,
        List<String> permissions
) {
    public static UserDto of(AppUser u) {
        return new UserDto(u.getId(), u.getUsername(), u.getDisplayName(),
                u.isAdmin(), u.getPermissions());
    }
}
