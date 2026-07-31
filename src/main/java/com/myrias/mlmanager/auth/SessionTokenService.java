package com.myrias.mlmanager.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import java.util.HexFormat;

/**
 * Sessão = token assinado (stateless), igual ao FastAPI: "&lt;userId&gt;.&lt;issued&gt;.&lt;hmac&gt;".
 * O userId viaja dentro do token assinado, então o servidor sabe QUEM é sem
 * guardar estado — sobrevive a restart. Espelho de backend/app/main.py.
 */
@Service
public class SessionTokenService {

    public static final String COOKIE_NAME = "app_session";

    /** Default commitado no application.yml — serve pro dev, nunca pra um deploy. */
    private static final String INSECURE_DEFAULT_KEY = "dev-insecure-change-me";

    /** Sessão expira em 30 dias. O `issued` já viajava no token; agora é lido. */
    private static final long MAX_AGE_SECONDS = 30L * 24 * 3600;

    private final byte[] signingKey;

    public SessionTokenService(@Value("${app.secret-key}") String secretKey,
                              Environment env) {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException("app.secret-key não configurado");
        }
        // Chave previsível = qualquer um forja o cookie de userId=1 e entra como
        // admin. Fora do dev isso tem de derrubar o boot, não virar aviso no log.
        if (INSECURE_DEFAULT_KEY.equals(secretKey) && !env.acceptsProfiles(Profiles.of("dev"))) {
            throw new IllegalStateException(
                    "APP_SECRET_KEY precisa ser definida fora do profile dev "
                    + "(a chave default é pública — permite forjar sessão de admin).");
        }
        this.signingKey = secretKey.getBytes(StandardCharsets.UTF_8);
    }

    /** Token de sessão no request, ou null. Mora aqui junto do COOKIE_NAME. */
    public static String readCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie c : request.getCookies()) {
            if (COOKIE_NAME.equals(c.getName())) return c.getValue();
        }
        return null;
    }

    public String make(long userId) {
        return make(userId, System.currentTimeMillis() / 1000);
    }

    /** Visível pro teste conseguir forjar um token antigo com assinatura válida. */
    String make(long userId, long issuedAtSeconds) {
        String payload = userId + "." + issuedAtSeconds;
        return payload + "." + sign(payload);
    }

    /** Valida a assinatura e a idade, e devolve o userId, ou vazio se inválido. */
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
            long issued = Long.parseLong(parts[1]);
            if ((System.currentTimeMillis() / 1000) - issued > MAX_AGE_SECONDS) {
                return Optional.empty();
            }
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
