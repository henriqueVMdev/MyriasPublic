package com.hrb.mlmanager.meli;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Fluxo OAuth do Mercado Livre (PKCE) e gestão das contas conectadas.
 * Espelho de backend/app/services/meli_auth.py.
 *
 * Diferença deliberada do Python: lá um MeliAuthService nasce por request e o
 * cache de token morre junto; aqui o serviço é singleton, então o cache de
 * token ({@link #tokenCache}) vive no app inteiro — menos queries entre requests,
 * mantendo o refresh proativo de 5 min. ponytail: cache in-process; se escalar
 * para várias réplicas, externalizar (Redis) ou aceitar 1 refresh por réplica.
 */
@Service
public class MeliAuthService {

    private static final Logger log = LoggerFactory.getLogger(MeliAuthService.class);

    private static final String AUTH_URL = "https://auth.mercadolivre.com.br";
    private static final String API_URL = "https://api.mercadolibre.com";
    private static final int MAX_ACCOUNTS = 4;
    private static final long REFRESH_SKEW_SECONDS = 300; // refresh 5 min antes de expirar

    /** Chave do cache para "conta ativa" (user_id null no Python). ML user_ids são positivos. */
    private static final Long ACTIVE_KEY = -1L;

    private final MeliTokenRepository tokens;
    private final PkceStateRepository pkceStates;
    private final AuthEventRepository authEvents;

    private final String appId;
    private final String secretKey;
    private final String redirectUri;

    private final RestClient http = RestClient.create();
    private final SecureRandom random = new SecureRandom();

    // user_id (ou ACTIVE_KEY) -> (accessToken, expiresAt)
    private final Map<Long, CachedToken> tokenCache = new ConcurrentHashMap<>();

    public MeliAuthService(MeliTokenRepository tokens, PkceStateRepository pkceStates,
                           AuthEventRepository authEvents,
                           @Value("${meli.app-id:}") String appId,
                           @Value("${meli.secret-key:}") String secretKey,
                           @Value("${meli.redirect-uri:http://localhost:5173/api/auth/callback}") String redirectUri) {
        this.tokens = tokens;
        this.pkceStates = pkceStates;
        this.authEvents = authEvents;
        this.appId = appId;
        this.secretKey = secretKey;
        this.redirectUri = redirectUri;
    }

    private record CachedToken(String accessToken, Instant expiresAt) {}

    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("expires_in") long expiresIn,
            @JsonProperty("user_id") Long userId,
            String scope) {}

    private static Long cacheKey(Long userId) {
        return userId == null ? ACTIVE_KEY : userId;
    }

    public void invalidateTokenCache(Long userId) {
        if (userId == null) {
            tokenCache.clear();
        } else {
            tokenCache.remove(userId);
        }
    }

    // ---- OAuth: authorize + callback ----------------------------------------

    /** Gera a URL de autorização com PKCE e persiste o verifier pelo state. */
    @Transactional
    public String getAuthUrl(String state) {
        String verifier = generateVerifier();
        String challenge = codeChallenge(verifier);
        if (state != null && !state.isEmpty()) {
            pkceStates.save(new PkceState(state, verifier));
        }
        StringBuilder params = new StringBuilder()
                .append("response_type=code")
                .append("&client_id=").append(enc(appId))
                .append("&redirect_uri=").append(enc(redirectUri))
                .append("&code_challenge=").append(enc(challenge))
                .append("&code_challenge_method=S256");
        if (state != null && !state.isEmpty()) {
            params.append("&state=").append(enc(state));
        }
        return AUTH_URL + "/authorization?" + params;
    }

    /** Troca o authorization code por tokens e salva a conta. */
    @Transactional
    public MeliToken exchangeCode(String code, String state) {
        String verifier = null;
        if (state != null && !state.isEmpty()) {
            Optional<PkceState> pkce = pkceStates.findById(state);
            if (pkce.isPresent()) {
                verifier = pkce.get().getCodeVerifier();
                pkceStates.deleteById(state);
            }
        }
        if (verifier == null) {
            throw new IllegalStateException("PKCE code_verifier não encontrado. Reinicie o fluxo de login.");
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", appId);
        form.add("client_secret", secretKey);
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        form.add("code_verifier", verifier);

        TokenResponse data;
        try {
            data = postToken(form);
        } catch (RestClientResponseException e) {
            String msg = "OAuth exchange failed (" + e.getStatusCode().value() + "): " + e.getResponseBodyAsString();
            log.error(msg);
            logAuthEvent(false, null, null, msg);
            throw new IllegalStateException(msg, e);
        }

        String nickname = fetchNickname(data.accessToken(), data.userId());
        MeliToken token = saveToken(data, nickname);
        logAuthEvent(true, token.getUserId(), token.getNickname(), null);
        return token;
    }

    private String fetchNickname(String accessToken, Long mlUserId) {
        try {
            Map<?, ?> body = http.get()
                    .uri(API_URL + "/users/" + mlUserId)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(Map.class);
            Object nick = body == null ? null : body.get("nickname");
            if (nick != null) return nick.toString();
        } catch (Exception ignored) {
            // nickname é cosmético — falha não bloqueia o login
        }
        return "Conta " + mlUserId;
    }

    // ---- Refresh -------------------------------------------------------------

    /** Renova o par de tokens via refresh_token e persiste. */
    @Transactional
    public MeliToken refreshAccessToken(MeliToken token) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("client_id", appId);
        form.add("client_secret", secretKey);
        form.add("refresh_token", token.getRefreshToken());

        TokenResponse data;
        try {
            data = postToken(form);
        } catch (RestClientResponseException e) {
            log.error("Token refresh failed: {} — {}", e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new IllegalStateException("Token refresh failed: " + e.getResponseBodyAsString(), e);
        }

        token.setAccessToken(data.accessToken());
        token.setRefreshToken(data.refreshToken());
        token.setExpiresAt(Instant.now().plusSeconds(data.expiresIn()));
        tokens.save(token);
        log.info("Token renovado para user_id={}", token.getUserId());
        return token;
    }

    /**
     * Força refresh ignorando o expires_at — usado após um 401, quando o token
     * pode estar inválido apesar de não ter expirado (revogado server-side,
     * relógio defasado). Retorna o novo access_token, ou null se falhar.
     */
    @Transactional
    public String forceRefreshToken(Long userId) {
        invalidateTokenCache(userId);
        MeliToken token = getToken(userId).orElse(null);
        if (token == null) return null;
        try {
            token = refreshAccessToken(token);
        } catch (Exception e) {
            log.error("force_refresh_token falhou para user_id={}: {}", userId, e.getMessage());
            return null;
        }
        tokenCache.put(cacheKey(userId), new CachedToken(token.getAccessToken(), token.getExpiresAt()));
        return token.getAccessToken();
    }

    /** Retorna um access_token válido para a conta indicada (ou a ativa). */
    @Transactional
    public String ensureValidToken(Long userId) {
        Instant now = Instant.now();
        Long key = cacheKey(userId);

        CachedToken cached = tokenCache.get(key);
        if (cached != null) {
            if (now.isBefore(cached.expiresAt().minusSeconds(REFRESH_SKEW_SECONDS))) {
                return cached.accessToken();
            }
            tokenCache.remove(key);
        }

        MeliToken token = getToken(userId).orElseThrow(
                () -> new IllegalStateException("Nenhum token encontrado. Faça login via OAuth."));

        if (!now.isBefore(token.getExpiresAt().minusSeconds(REFRESH_SKEW_SECONDS))) {
            log.info("Token próximo de expirar para user_id={}, renovando...", token.getUserId());
            token = refreshAccessToken(token);
        }

        tokenCache.put(key, new CachedToken(token.getAccessToken(), token.getExpiresAt()));
        return token.getAccessToken();
    }

    // ---- Status e contas -----------------------------------------------------

    @Transactional(readOnly = true)
    public Map<String, Object> getStatus(Long userId) {
        MeliToken token = getToken(userId).orElse(null);
        if (token == null) {
            return Map.of("authenticated", false);
        }
        Instant now = Instant.now();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("authenticated", true);
        out.put("user_id", token.getUserId());
        out.put("nickname", nicknameOf(token));
        out.put("expires_at", token.getExpiresAt().toString());
        out.put("expires_in_seconds", Math.max(0, ChronoUnit.SECONDS.between(now, token.getExpiresAt())));
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listAccounts() {
        Instant now = Instant.now();
        List<Map<String, Object>> accounts = new ArrayList<>();
        for (MeliToken t : tokens.findAllByOrderByActiveDescCreatedAtAsc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("user_id", t.getUserId());
            m.put("nickname", nicknameOf(t));
            m.put("is_active", t.isActive());
            m.put("expires_at", t.getExpiresAt().toString());
            m.put("expired", !now.isBefore(t.getExpiresAt()));
            accounts.add(m);
        }
        return accounts;
    }

    @Transactional
    public Map<String, Object> setActiveAccount(Long userId) {
        // Carrega todas uma vez; as entidades ficam gerenciadas e o flush da
        // transação persiste as mudanças de is_active (sem save explícito).
        List<MeliToken> all = tokens.findAll();
        if (all.stream().noneMatch(t -> t.getUserId().equals(userId))) {
            throw new IllegalArgumentException("Conta " + userId + " não encontrada");
        }
        for (MeliToken t : all) {
            t.setActive(t.getUserId().equals(userId));
        }
        invalidateTokenCache(null); // a "conta ativa" mudou
        log.info("Conta ativa alterada para user_id={}", userId);
        return getStatus(userId);
    }

    @Transactional
    public void removeAccount(Long userId) {
        MeliToken token = tokens.findByUserId(userId).orElse(null);
        if (token == null) return;
        boolean wasActive = token.isActive();
        tokens.delete(token);
        invalidateTokenCache(userId);
        if (wasActive) {
            invalidateTokenCache(null);
            tokens.findAll().stream().findFirst().ifPresent(next -> {
                next.setActive(true);
                tokens.save(next);
            });
        }
        log.info("Conta removida: user_id={}", userId);
    }

    @Transactional
    public void logout() {
        getToken(null).ifPresent(t -> removeAccount(t.getUserId()));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getLastAuthEvents(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AuthEvent e : authEvents.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("success", e.isSuccess());
            m.put("user_id", e.getUserId());
            m.put("nickname", e.getNickname());
            m.put("error", e.getError());
            m.put("created_at", e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
            out.add(m);
        }
        return out;
    }

    // ---- Internos ------------------------------------------------------------

    private Optional<MeliToken> getToken(Long userId) {
        if (userId != null) {
            return tokens.findByUserId(userId);
        }
        // Conta ativa; fallback para a primeira se nenhuma marcada.
        Optional<MeliToken> active = tokens.findFirstByActiveTrue();
        return active.isPresent() ? active : tokens.findAll().stream().findFirst();
    }

    private MeliToken saveToken(TokenResponse data, String nickname) {
        Instant expiresAt = Instant.now().plusSeconds(data.expiresIn());
        Long mlUserId = data.userId();

        MeliToken existing = tokens.findByUserId(mlUserId).orElse(null);
        if (existing != null) {
            existing.setAccessToken(data.accessToken());
            existing.setRefreshToken(data.refreshToken());
            existing.setExpiresAt(expiresAt);
            existing.setScope(data.scope() == null ? "" : data.scope());
            if (nickname != null && !nickname.isEmpty()) {
                existing.setNickname(nickname);
            }
            tokens.save(existing);
            invalidateTokenCache(mlUserId);
            invalidateTokenCache(null);
            log.info("Token salvo para user_id={} ({})", mlUserId, nickname);
            return existing;
        }

        if (tokens.count() >= MAX_ACCOUNTS) {
            throw new IllegalStateException(
                    "Limite de " + MAX_ACCOUNTS + " contas atingido. Remova uma conta antes de conectar outra.");
        }
        for (MeliToken t : tokens.findAll()) {
            if (t.isActive()) t.setActive(false);
        }
        MeliToken token = new MeliToken(mlUserId, nickname, data.accessToken(), data.refreshToken(),
                expiresAt, data.scope() == null ? "" : data.scope(), true);
        tokens.save(token);
        invalidateTokenCache(null);
        log.info("Token salvo para user_id={} ({})", mlUserId, nickname);
        return token;
    }

    private void logAuthEvent(boolean success, Long userId, String nickname, String error) {
        authEvents.save(new AuthEvent(success, userId, nickname, error));
    }

    private TokenResponse postToken(MultiValueMap<String, String> form) {
        return http.post()
                .uri(API_URL + "/oauth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);
    }

    private static String nicknameOf(MeliToken t) {
        return (t.getNickname() == null || t.getNickname().isEmpty())
                ? "Conta " + t.getUserId() : t.getNickname();
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    private String generateVerifier() {
        byte[] bytes = new byte[64];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // package-private: testável (vetor RFC 7636) sem expor publicamente.
    static String codeChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao gerar PKCE challenge", e);
        }
    }
}
