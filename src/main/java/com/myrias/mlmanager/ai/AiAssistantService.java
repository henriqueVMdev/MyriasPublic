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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Loop do agente: modelo ⇄ tools até resposta final ou tool de escrita.
 * Cada comando é auditado com usuário, tokens, custo e ferramentas consultadas.
 */
@Service
public class AiAssistantService {

    private static final Logger log = LoggerFactory.getLogger(AiAssistantService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    /** Teto do histórico que o cliente pode mandar. Sem isso quem chama a API
     *  escolhe quantos tokens de prompt nós pagamos — vezes o nº de iterações. */
    private static final int MAX_CLIENT_MESSAGES = 20;   // ~10 turnos
    private static final int MAX_MESSAGE_CHARS = 4000;   // ~1k tokens por mensagem

    private final OpenRouterClient openRouter;
    private final AiModelSettingsService modelSettings;
    private final AiToolRegistry tools;
    private final PendingActionStore pendingActions;
    private final MeliAuthService meliAuth;
    private final AiCustomizationService customization;
    private final AiAuditService audit;
    private final AiQuotaService quota;
    private final int maxTokens;
    private final int maxIterations;

    public AiAssistantService(OpenRouterClient openRouter, AiModelSettingsService modelSettings,
                              AiToolRegistry tools, PendingActionStore pendingActions,
                              MeliAuthService meliAuth, AiCustomizationService customization,
                              AiAuditService audit, AiQuotaService quota,
                              @Value("${openrouter.max-tokens:1500}") int maxTokens,
                              @Value("${openrouter.max-iterations:8}") int maxIterations) {
        this.openRouter = openRouter;
        this.modelSettings = modelSettings;
        this.tools = tools;
        this.pendingActions = pendingActions;
        this.meliAuth = meliAuth;
        this.customization = customization;
        this.audit = audit;
        this.quota = quota;
        this.maxTokens = maxTokens;
        this.maxIterations = maxIterations;
    }

    /**
     * @param quotaKey balde do teto de gasto — IP na demo, usuário fora dela
     *                 (resolvido no AiController, que é quem tem o request).
     */
    public Map<String, Object> chat(AppUser user, JsonNode clientMessages, String quotaKey) {
        // Antes do audit.begin: request bloqueado não deve virar linha de auditoria.
        quota.require(quotaKey);
        String model = modelSettings.currentModel();
        AiAuditService.Tracker tracker = audit.begin(user, lastUserCommand(clientMessages), model);
        try {
            ArrayNode messages = MAPPER.createArrayNode();
            messages.add(systemMessage());
            copyClientMessages(clientMessages, messages);
            ArrayNode toolDefs = tools.toolDefinitions(user);
            Set<String> offered = new HashSet<>();
            for (JsonNode def : toolDefs) offered.add(def.path("function").path("name").asText());
            List<String> toolEvents = new ArrayList<>();

            for (int i = 0; i < maxIterations; i++) {
                // O loop pode dar 8 voltas: sem re-checar, um único comando come o
                // orçamento do dia inteiro.
                if (i > 0) quota.requireGlobal();
                ObjectNode payload = MAPPER.createObjectNode();
                payload.put("model", model);
                payload.put("user", String.valueOf(user.getId()));
                // Teto do que uma única resposta pode custar. É um assistente de
                // dados: resposta curta e determinística basta e sai mais barato.
                payload.put("max_tokens", maxTokens);
                payload.put("temperature", 0.2);
                // Sem isso o OpenRouter não devolve usage.cost e a auditoria fica em $0.
                payload.set("usage", MAPPER.createObjectNode().put("include", true));
                payload.set("messages", messages);
                if (!toolDefs.isEmpty()) payload.set("tools", toolDefs);

                JsonNode response = openRouter.chat(payload);
                tracker.capture(response);
                quota.record(quotaKey, tracker.lastCost());
                JsonNode message = response.path("choices").path(0).path("message");
                JsonNode toolCalls = message.path("tool_calls");
                if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                    return completed(tracker, message.path("content").asText(""),
                            toolEvents, null);
                }

                messages.add(message.deepCopy());
                for (JsonNode call : toolCalls) {
                    String name = call.path("function").path("name").asText();
                    JsonNode args = parseArgs(call.path("function").path("arguments").asText("{}"));

                    // O modelo só pode chamar ferramentas oferecidas a este usuário.
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
                            toolEvents.add("erro em " + name + ": " + e.getMessage());
                            messages.add(toolResultMessage(call.path("id").asText(),
                                    errorJson(e.getMessage())));
                            continue;
                        }
                        var action = pendingActions.create(user.getId(), name, args, summary);
                        String text = message.path("content").asText("");
                        Map<String, Object> pending = new LinkedHashMap<>();
                        pending.put("id", action.id());
                        pending.put("tool", name);
                        pending.put("summary", summary);
                        pending.put("args", args);
                        return completed(tracker,
                                text.isBlank()
                                        ? "Preparei a ação abaixo — confirme para executar."
                                        : text,
                                toolEvents, pending);
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
            return completed(tracker,
                    "Não consegui concluir em " + maxIterations
                            + " passos. Tente uma pergunta mais específica.",
                    toolEvents, null);
        } catch (RuntimeException e) {
            tracker.failure(e.getMessage());
            throw e;
        }
    }

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
                Map<String, Object> account = accounts.get(i);
                if (i > 0) sb.append(", ");
                sb.append(account.get("nickname")).append(" (id ")
                        .append(account.get("user_id")).append(")");
            }
            sb.append(". ");
        }
        sb.append("Responda em português, direto ao ponto, valores em R$. ")
          .append("Use as tools para consultar dados reais antes de afirmar números. ")
          .append("Para alterações (preço, estoque, status, título, promoções) chame a tool de ")
          .append("escrita — ela NÃO executa: gera um pedido de confirmação pro usuário. ")
          .append("Nunca diga que uma alteração foi feita antes de o usuário confirmar. ")
          .append("Você não executa nada em segundo plano: a execução acontece na hora, ")
          .append("quando o usuário clica em Confirmar. Nunca diga que está executando ou ")
          .append("que avisará quando terminar. ")
          .append("Para concorrência (quando as tools estiverem disponíveis): analyze_item_competition ")
          .append("compara um anúncio nosso com os rivais; inspect_listing abre os detalhes de um ")
          .append("concorrente vencedor pra explicar a performance; get_category_discovery mostra os ")
          .append("mais vendidos e termos em alta da categoria. Ao aconselhar, aponte o que o líder faz ")
          .append("diferente (preço, frete, fotos, atributos, tipo de anúncio) e o que ajustar no nosso.")
          .append(customization.promptContext());
        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", "system");
        message.put("content", sb.toString());
        return message;
    }

    private static Map<String, Object> completed(AiAuditService.Tracker tracker,
                                                  String reply, List<String> events,
                                                  Map<String, Object> pendingAction) {
        tracker.success(reply, events);
        return done(reply, events, pendingAction);
    }

    /**
     * Só roles user/assistant com content texto — descarta o resto. O histórico
     * vem do cliente, então é entrada não confiável: cada mensagem é truncada em
     * MAX_MESSAGE_CHARS e só as últimas MAX_CLIENT_MESSAGES entram. Espelha o
     * MAX_RESULT_CHARS do AiToolRegistry, que já limita o outro lado.
     */
    private static void copyClientMessages(JsonNode clientMessages, ArrayNode out) {
        if (clientMessages == null || !clientMessages.isArray()) return;
        List<ObjectNode> valid = new ArrayList<>();
        for (JsonNode message : clientMessages) {
            String role = message.path("role").asText("");
            String content = message.path("content").asText("");
            if ((role.equals("user") || role.equals("assistant")) && !content.isBlank()) {
                ObjectNode copy = MAPPER.createObjectNode();
                copy.put("role", role);
                copy.put("content", content.length() > MAX_MESSAGE_CHARS
                        ? content.substring(0, MAX_MESSAGE_CHARS)
                        : content);
                valid.add(copy);
            }
        }
        // A cauda é o contexto relevante; o começo da conversa é o que se descarta.
        int from = Math.max(0, valid.size() - MAX_CLIENT_MESSAGES);
        for (ObjectNode copy : valid.subList(from, valid.size())) out.add(copy);
    }

    private static String lastUserCommand(JsonNode clientMessages) {
        String command = "";
        if (clientMessages != null && clientMessages.isArray()) {
            for (JsonNode message : clientMessages) {
                if ("user".equals(message.path("role").asText())) {
                    String content = message.path("content").asText("").strip();
                    if (!content.isBlank()) command = content;
                }
            }
        }
        return command.isBlank() ? "(comando sem texto)" : command;
    }

    private static JsonNode parseArgs(String raw) {
        try {
            JsonNode parsed = MAPPER.readTree(raw == null || raw.isBlank() ? "{}" : raw);
            return parsed.isObject() ? parsed : MAPPER.createObjectNode();
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private static String errorJson(String message) {
        ObjectNode value = MAPPER.createObjectNode();
        value.put("error", message == null ? "erro desconhecido" : message);
        return value.toString();
    }

    private static ObjectNode toolResultMessage(String toolCallId, String result) {
        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", "tool");
        message.put("tool_call_id", toolCallId);
        message.put("content", result);
        return message;
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
            case "get_item_pictures" -> "consultou fotos do anúncio " + args.path("item_id").asText("");
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
