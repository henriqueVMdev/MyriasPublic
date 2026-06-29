package com.hrb.mlmanager.meli;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Tokens OAuth de uma conta do Mercado Livre. Espelho de backend/app/models/auth.py
 * ({@code MeliToken}). {@code userId} é o id da conta ML (BigInteger no Python),
 * não o usuário do painel. Várias contas podem coexistir; {@code active} marca
 * qual responde por padrão quando nenhum user_id é passado.
 */
@Entity
@Table(name = "meli_tokens")
public class MeliToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Id da conta no Mercado Livre (não confundir com AppUser do painel). */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(length = 100)
    private String nickname;

    @Column(name = "access_token", nullable = false, columnDefinition = "text")
    private String accessToken;

    @Column(name = "refresh_token", nullable = false, columnDefinition = "text")
    private String refreshToken;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(columnDefinition = "text")
    private String scope;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    protected MeliToken() {}

    public MeliToken(Long userId, String nickname, String accessToken, String refreshToken,
                     Instant expiresAt, String scope, boolean active) {
        this.userId = userId;
        this.nickname = nickname;
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.expiresAt = expiresAt;
        this.scope = scope;
        this.active = active;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getNickname() { return nickname; }
    public String getAccessToken() { return accessToken; }
    public String getRefreshToken() { return refreshToken; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getScope() { return scope; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }

    public void setNickname(String nickname) { this.nickname = nickname; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public void setScope(String scope) { this.scope = scope; }
    public void setActive(boolean active) { this.active = active; }
}
