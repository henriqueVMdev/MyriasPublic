package com.hrb.mlmanager.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.HexFormat;

/**
 * Sessão = token assinado (stateless), igual ao FastAPI: "&lt;userId&gt;.&lt;issued&gt;.&lt;hmac&gt;".
 * O userId viaja dentro do token assinado, então o servidor sabe QUEM é sem
 * guardar estado — sobrevive a restart. Espelho de backend/app/main.py.
 */
@Service
public class SessionTokenService {

    public static final String COOKIE_NAME = "app_session";

    private final byte[] signingKey;

    public SessionTokenService(@Value("${app.secret-key}") String secretKey) {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException("app.secret-key não configurado");
        }
        this.signingKey = secretKey.getBytes(StandardCharsets.UTF_8);
    }

    public String make(long userId) {
        String payload = userId + "." + (System.currentTimeMillis() / 1000);
        return payload + "." + sign(payload);
    }

    /** Valida a assinatura e devolve o userId, ou vazio se inválido. */
    public Optional<Long> verify(String token) {
        if (token == null) return Optional.empty();
        String[] parts = token.split("\\.");
        if (parts.length != 3) return Optional.empty();
        String payload = parts[0] + "." + parts[1];
        String expected = sign(payload);
        // Comparação em tempo constante pra não vazar a assinatura por timing.
        if (!MessageDigest.isEqual(
                parts[2].getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(parts[0]));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao assinar token", e);
        }
    }
}
