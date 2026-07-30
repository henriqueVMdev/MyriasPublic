package com.hrb.mlmanager.meli;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Guarda o PKCE code_verifier no banco (chaveado pelo {@code state}) para o
 * fluxo OAuth sobreviver a reload/restart do servidor entre o /login e o
 * /callback. Espelho de backend/app/models/auth.py ({@code PkceState}).
 */
@Entity
@Table(name = "pkce_states")
public class PkceState {

    @Id
    @Column(length = 128)
    private String state;

    @Column(name = "code_verifier", nullable = false, columnDefinition = "text")
    private String codeVerifier;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    protected PkceState() {}

    public PkceState(String state, String codeVerifier) {
        this.state = state;
        this.codeVerifier = codeVerifier;
    }

    public String getState() { return state; }
    public String getCodeVerifier() { return codeVerifier; }
}
