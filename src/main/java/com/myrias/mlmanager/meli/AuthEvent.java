package com.myrias.mlmanager.meli;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Registro de cada tentativa de OAuth (sucesso ou falha) para diagnóstico
 * remoto. Espelho de backend/app/models/auth.py ({@code AuthEvent}).
 */
@Entity
@Table(name = "auth_events")
public class AuthEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "user_id")
    private Long userId;

    @Column(length = 100)
    private String nickname;

    @Column(columnDefinition = "text")
    private String error;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    protected AuthEvent() {}

    public AuthEvent(boolean success, Long userId, String nickname, String error) {
        this.success = success;
        this.userId = userId;
        this.nickname = nickname;
        this.error = error;
    }

    public Long getId() { return id; }
    public boolean isSuccess() { return success; }
    public Long getUserId() { return userId; }
    public String getNickname() { return nickname; }
    public String getError() { return error; }
    public Instant getCreatedAt() { return createdAt; }
}
