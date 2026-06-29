package com.hrb.mlmanager.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Check da lógica de assinatura/validação do token — não precisa de Spring nem banco. */
class SessionTokenServiceTest {

    private final SessionTokenService svc = new SessionTokenService("uma-chave-secreta");

    @Test
    void roundTrip_recuperaUserId() {
        String token = svc.make(42L);
        assertEquals(42L, svc.verify(token).orElseThrow());
    }

    @Test
    void assinaturaAdulterada_eRejeitada() {
        String token = svc.make(7L);
        String forjado = token.substring(0, token.length() - 1)
                + (token.endsWith("a") ? "b" : "a");
        assertTrue(svc.verify(forjado).isEmpty());
    }

    @Test
    void chaveDiferente_naoValida() {
        String token = svc.make(7L);
        SessionTokenService outra = new SessionTokenService("outra-chave");
        assertTrue(outra.verify(token).isEmpty());
    }

    @Test
    void tokenMalformado_naoQuebra() {
        assertTrue(svc.verify(null).isEmpty());
        assertTrue(svc.verify("lixo").isEmpty());
        assertTrue(svc.verify("a.b").isEmpty());
    }
}
