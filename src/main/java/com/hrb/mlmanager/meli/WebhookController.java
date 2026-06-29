package com.hrb.mlmanager.meli;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhooks do Mercado Livre. Espelho de backend/app/api/webhooks.py.
 *
 * AVISO DE SEGURANÇA: /api/webhooks/ml está em OPEN_PATHS (AppAuthFilter) — não
 * exige sessão. Qualquer cliente pode chamar. Enquanto o handler só faz log, não
 * há risco. MAS antes de adicionar qualquer efeito colateral (escrita no banco,
 * chamada à API do ML, alteração de status de itens), é obrigatório validar a
 * origem:
 *   1. Conferir body.user_id contra contas conectadas (MeliTokenRepository);
 *      user_id desconhecido → 400 sem processar.
 *   2. Se o ML enviar assinatura HMAC, validar antes de qualquer lógica.
 *   3. Não usar IP de origem como única defesa (proxies/NAT).
 * Remova este aviso só após implementar a validação.
 */
@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    @PostMapping("/ml")
    public Map<String, String> mlWebhook(@RequestBody(required = false) JsonNode body) {
        String topic = textOr(body, "topic", "unknown");
        String resource = textOr(body, "resource", "");
        log.info("Webhook ML: topic={}, resource={}", topic, resource);
        return Map.of("status", "ok");
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        if (node == null || !node.hasNonNull(field)) return fallback;
        return node.get(field).asText(fallback);
    }
}
