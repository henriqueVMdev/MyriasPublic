package com.hrb.mlmanager.meli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * PKCE S256: challenge = base64url(sha256(verifier)) sem padding.
 * Vetor oficial do RFC 7636, Apêndice B — se o cálculo quebrar, o ML rejeita
 * o exchange e o login OAuth para de funcionar.
 */
class MeliPkceTest {

    @Test
    void challengeMatchesRfc7636Vector() {
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        String expected = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
        assertEquals(expected, MeliAuthService.codeChallenge(verifier));
    }
}
