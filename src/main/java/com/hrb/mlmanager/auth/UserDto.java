package com.hrb.mlmanager.auth;

import java.util.List;

/** Visão pública do usuário, mesmo shape do _user_public() do FastAPI. */
public record UserDto(
        Long id,
        String username,
        String displayName,
        boolean isAdmin,
        List<String> permissions
) {
    public static UserDto of(AppUser u) {
        return new UserDto(u.getId(), u.getUsername(), u.getDisplayName(),
                u.isAdmin(), u.getPermissions());
    }
}
