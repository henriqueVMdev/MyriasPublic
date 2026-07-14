package com.hrb.mlmanager.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Ações de escrita propostas pela IA aguardando confirmação humana.
 * Em memória com TTL — restart do app perde as pendências (aceitável, spec v1).
 * Amarradas ao usuário do painel: A não confirma ação proposta pra B.
 */
@Component
public class PendingActionStore {

    public record PendingAction(String id, long panelUserId, String tool, JsonNode args,
                                String summary, Instant expiresAt) {}

    private final Map<String, PendingAction> actions = new ConcurrentHashMap<>();
    private final Duration ttl;

    @Autowired
    public PendingActionStore() { this(Duration.ofMinutes(10)); }

    PendingActionStore(Duration ttl) { this.ttl = ttl; }

    public PendingAction create(long panelUserId, String tool, JsonNode args, String summary) {
        purgeExpired();
        PendingAction action = new PendingAction(UUID.randomUUID().toString(), panelUserId,
                tool, args, summary, Instant.now().plus(ttl));
        actions.put(action.id(), action);
        return action;
    }

    /** Remove e devolve; null = inexistente, expirada ou de outro usuário.
     *  A remoção só acontece quando a validação passa — uma tentativa de outro
     *  usuário não pode destruir a ação pendente do dono legítimo. */
    public PendingAction consume(String id, long panelUserId) {
        Instant now = Instant.now();
        PendingAction[] matched = new PendingAction[1];
        actions.computeIfPresent(id, (key, action) -> {
            if (action.panelUserId() == panelUserId && action.expiresAt().isAfter(now)) {
                matched[0] = action;
                return null; // remove só no acerto
            }
            return action; // usuário errado ou expirada: deixa como está
        });
        return matched[0];
    }

    public void discard(String id, long panelUserId) {
        actions.computeIfPresent(id, (key, action) -> action.panelUserId() == panelUserId ? null : action);
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        actions.values().removeIf(a -> a.expiresAt().isBefore(now));
    }
}
