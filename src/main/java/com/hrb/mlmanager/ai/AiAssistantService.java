package com.hrb.mlmanager.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hrb.mlmanager.auth.AppUser;
import com.hrb.mlmanager.meli.MeliAuthService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Loop do agente: modelo ⇄ tools até resposta final ou tool de escrita.
 * Leituras executam na hora; escrita vira pending action (confirmação humana
 * no AiController). Backend stateless: o histórico vem inteiro do widget.
 */
@Service
public class AiAssistantService {

    private static final Logger log = LoggerFactory.getLogger(AiAssistantService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_ITERATIONS = 8;
    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    private final OpenRouterClient openRouter;
    private final AiToolRegistry tools;
    private final PendingActionStore pendingActions;
    private final MeliAuthService meliAuth;

    public AiAssistantService(OpenRouterClient openRouter, AiToolRegistry tools,
                              PendingActionStore pendingActions, MeliAuthService meliAuth) {
        this.openRouter = openRouter;
        this.tools = tools;
        this.pendingActions = pendingActions;
        this.meliAuth = meliAuth;
    }

    public Map<String, Object> chat(AppUser user, JsonNode clientMessages, String requestedModel) {
        String model = openRouter.resolveModel(requestedModel);
        ArrayNode messages = MAPPER.createArrayNode();
        messages.add(systemMessage());
        copyClientMessages(clientMessages, messages);
        ArrayNode toolDefs = tools.toolDefinitions(user);
        Set<String> offered = new HashSet<>();
        for (JsonNode def : toolDefs) offered.add(def.path("function").path("name").asText());
        List<String> toolEvents = new ArrayList<>();

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("model", model);
            payload.set("messages", messages);
            if (!toolDefs.isEmpty()) payload.set("tools", toolDefs);

            JsonNode message = openRouter.chat(payload).path("choices").path(0).path("message");
            JsonNode toolCalls = message.path("tool_calls");
            if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                return done(message.path("content").asText(""), toolEvents, null);
            }

            messages.add(message.deepCopy()); // turno assistant com tool_calls
            for (JsonNode call : toolCalls) {
                String name = call.path("function").path("name").asText();
                JsonNode args = parseArgs(call.path("function").path("arguments").asText("{}"));

                // Defesa: só despacha tools realmente oferecidas a este usuário
                // (toolDefinitions filtra por permissão; o modelo pode alucinar).
                if (!offered.contains(name)) {
                    log.warn("Modelo chamou tool não oferecida: {}", name);
                    toolEvents.add("erro em " + name + ": tool não disponível");
                    messages.add(toolResultMessage(call.path("id").asText(),
                            errorJson("tool não disponível: " + name)));
                    continue;
                }

                if (tools.isWriteTool(name)) {
                    String summary;
                    try {
                        summary = tools.summarize(name, args);
                    } catch (IllegalArgumentException e) {
                        log.warn("Args inválidos pra tool de escrita {}: {}", name, e.getMessage());
                        String result = errorJson(e.getMessage());
                        toolEvents.add("erro em " + name + ": " + e.getMessage());
                        messages.add(toolResultMessage(call.path("id").asText(), result));
                        continue;
                    }
                    var action = pendingActions.create(user.getId(), name, args, summary);
                    String text = message.path("content").asText("");
                    Map<String, Object> pending = new LinkedHashMap<>();
                    pending.put("id", action.id());
                    pending.put("tool", name);
                    pending.put("summary", summary);
                    pending.put("args", args);
                    return done(text.isBlank()
                            ? "Preparei a ação abaixo — confirme para executar."
                            : text, toolEvents, pending);
                }

                String result;
                try {
                    result = tools.executeRead(name, args);
                    toolEvents.add(eventLabel(name, args));
                } catch (Exception e) {
                    log.warn("Tool {} falhou: {}", name, e.getMessage());
                    result = errorJson(e.getMessage());
                    toolEvents.add("erro em " + name + ": " + e.getMessage());
                }
                messages.add(toolResultMessage(call.path("id").asText(), result));
            }
        }
        return done("Não consegui concluir em " + MAX_ITERATIONS
                + " passos. Tente uma pergunta mais específica.", toolEvents, null);
    }

    // ---- Internos -------------------------------------------------------------

    private ObjectNode systemMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append("Você é o assistente do HRB ML Manager, painel de gestão de anúncios do ")
          .append("Mercado Livre com múltiplas contas. Hoje é ")
          .append(LocalDate.now(SAO_PAULO)).append(". ");
        List<Map<String, Object>> accounts;
        try {
            accounts = meliAuth.listAccounts();
        } catch (Exception e) {
            accounts = List.of();
        }
        if (accounts.isEmpty()) {
            sb.append("Nenhuma conta ML conectada no momento — informe isso se o usuário pedir dados. ");
        } else {
            sb.append("Contas conectadas: ");
            for (int i = 0; i < accounts.size(); i++) {
                Map<String, Object> a = accounts.get(i);
                if (i > 0) sb.append(", ");
                sb.append(a.get("nickname")).append(" (id ").append(a.get("user_id")).append(")");
            }
            sb.append(". ");
        }
        sb.append("Responda em português, direto ao ponto, valores em R$. ")
          .append("Use as tools para consultar dados reais antes de afirmar números. ")
          .append("Para alterações (preço, estoque, status, título, promoções) chame a tool de ")
          .append("escrita — ela NÃO executa: gera um pedido de confirmação pro usuário. ")
          .append("Nunca diga que uma alteração foi feita antes de o usuário confirmar.");
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("role", "system");
        msg.put("content", sb.toString());
        return msg;
    }

    /** Só roles user/assistant com content texto — descarta o resto (defesa). */
    private static void copyClientMessages(JsonNode clientMessages, ArrayNode out) {
        if (clientMessages == null || !clientMessages.isArray()) return;
        for (JsonNode m : clientMessages) {
            String role = m.path("role").asText("");
            String content = m.path("content").asText("");
            if ((role.equals("user") || role.equals("assistant")) && !content.isBlank()) {
                ObjectNode msg = MAPPER.createObjectNode();
                msg.put("role", role);
                msg.put("content", content);
                out.add(msg);
            }
        }
    }

    private static JsonNode parseArgs(String raw) {
        try {
            JsonNode n = MAPPER.readTree(raw == null || raw.isBlank() ? "{}" : raw);
            return n.isObject() ? n : MAPPER.createObjectNode();
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private static String errorJson(String message) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("error", message == null ? "erro desconhecido" : message);
        return n.toString();
    }

    private static ObjectNode toolResultMessage(String toolCallId, String result) {
        ObjectNode toolMsg = MAPPER.createObjectNode();
        toolMsg.put("role", "tool");
        toolMsg.put("tool_call_id", toolCallId);
        toolMsg.put("content", result);
        return toolMsg;
    }

    private static String eventLabel(String name, JsonNode args) {
        return switch (name) {
            case "list_accounts" -> "consultou as contas conectadas";
            case "get_dashboard_summary" -> "consultou o resumo do dashboard";
            case "get_dashboard_performance" ->
                    "consultou performance (" + args.path("days").asInt(30) + " dias)";
            case "get_dashboard_revenue" ->
                    "consultou faturamento (" + args.path("days").asInt(30) + " dias)";
            case "list_skus" -> "listou os SKUs";
            case "get_items_by_sku" -> "consultou anúncios do SKU " + args.path("sku").asText("");
            case "list_questions" -> "consultou perguntas";
            case "questions_stats" -> "consultou volume de perguntas";
            case "list_promotions" -> "listou promoções";
            case "list_promotion_items" -> "consultou anúncios da promoção";
            case "get_operation_logs" -> "consultou o histórico de operações";
            default -> "consultou " + name;
        };
    }

    private static Map<String, Object> done(String reply, List<String> events,
                                            Map<String, Object> pendingAction) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reply", reply);
        out.put("tool_events", events);
        out.put("pending_action", pendingAction);
        return out;
    }
}
