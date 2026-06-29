package com.hrb.mlmanager.auth;

import com.hrb.mlmanager.config.StringListJsonConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Usuário humano do painel (login fixo criado pelo admin).
 * Espelho de backend/app/models/user.py. A senha é hash bcrypt;
 * {@code permissions} é a lista de chaves de seção/ação (ver {@link Permissions}).
 */
@Entity
@Table(name = "app_users")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 60)
    private String username;

    @Column(name = "display_name", length = 120)
    private String displayName;

    @Column(name = "hashed_password", nullable = false, length = 255)
    private String hashedPassword;

    @Column(name = "is_admin", nullable = false)
    private boolean admin = false;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /** Lista de chaves serializada como JSON em coluna texto (portável Postgres/H2). */
    @Convert(converter = StringListJsonConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private List<String> permissions = List.of();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    protected AppUser() {}

    public AppUser(String username, String displayName, String hashedPassword,
                   boolean admin, List<String> permissions) {
        this.username = username;
        this.displayName = displayName;
        this.hashedPassword = hashedPassword;
        this.admin = admin;
        this.active = true;
        this.permissions = permissions == null ? List.of() : permissions;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public String getHashedPassword() { return hashedPassword; }
    public boolean isAdmin() { return admin; }
    public boolean isActive() { return active; }
    public List<String> getPermissions() { return permissions == null ? List.of() : permissions; }
}
