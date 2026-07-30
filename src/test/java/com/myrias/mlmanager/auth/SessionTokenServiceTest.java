package com.myrias.mlmanager.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Check da lógica de assinatura/validação do token — não precisa de Spring nem banco. */
class SessionTokenServiceTest {

    private static MockEnvironment env(String profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles.isEmpty() ? new String[0] : profiles.split(","));
        return env;
    }

    private final SessionTokenService svc =
            new SessionTokenService("uma-chave-secreta", env("dev"));

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
        SessionTokenService outra = new SessionTokenService("outra-chave", env("dev"));
        assertTrue(outra.verify(token).isEmpty());
    }

    @Test
    void tokenMalformado_naoQuebra() {
        assertTrue(svc.verify(null).isEmpty());
        assertTrue(svc.verify("lixo").isEmpty());
        assertTrue(svc.verify("a.b").isEmpty());
    }

    /** Assinatura válida mas velha: tem de ser rejeitada, senão a sessão é eterna. */
    @Test
    void tokenExpirado_eRejeitado() {
        long trintaEUmDiasAtras = (System.currentTimeMillis() / 1000) - (31L * 24 * 3600);
        String antigo = svc.make(42L, trintaEUmDiasAtras);
        assertTrue(svc.verify(antigo).isEmpty());

        long ontem = (System.currentTimeMillis() / 1000) - (24 * 3600);
        assertEquals(42L, svc.verify(svc.make(42L, ontem)).orElseThrow());
    }

    /** A chave default é pública (está no application.yml): fora do dev, não sobe. */
    @Test
    void chaveInseguraForaDoDev_derrubaOBoot() {
        assertThrows(IllegalStateException.class,
                () -> new SessionTokenService("dev-insecure-change-me", env("")));
        // No dev ela continua valendo, senão ninguém roda o projeto local.
        SessionTokenService dev = new SessionTokenService("dev-insecure-change-me", env("dev"));
        assertEquals(1L, dev.verify(dev.make(1L)).orElseThrow());
    }
}
