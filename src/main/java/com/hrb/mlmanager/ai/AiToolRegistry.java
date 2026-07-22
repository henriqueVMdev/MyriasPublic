package com.hrb.mlmanager.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hrb.mlmanager.auth.AppUser;
import com.hrb.mlmanager.competition.MeliCompetitionService;
import com.hrb.mlmanager.dashboard.DashboardService;
import com.hrb.mlmanager.meli.MeliAuthService;
import com.hrb.mlmanager.meli.MeliBulkService;
import com.hrb.mlmanager.meli.MeliPromotionsService;
import com.hrb.mlmanager.meli.MeliQuestionsService;
import com.hrb.mlmanager.ops.OperationLogService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Catálogo de tools do assistente: schemas (formato OpenAI) + dispatch para os
 * services existentes. Leituras executam direto; escritas (WRITE_TOOLS) viram
 * pending action e só executam no confirm (ver AiController).
 */
@Component
public class AiToolRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RESULT_CHARS = 15000;
    private static final int MAX_SKUS = 100;

    public static final Set<String> WRITE_TOOLS = Set.of(
            "bulk_update_items", "add_items_to_promotion", "remove_items_from_promotion");

    /** Tools que exigem permissão além de `assistente` (leituras livres ficam fora). */
    private static final Map<String, String> TOOL_PERMISSION = Map.ofEntries(
            Map.entry("get_dashboard_revenue", "dashboard_revenue"),
            Map.entry("bulk_update_items", "bulk_edit"),
            Map.entry("add_items_to_promotion", "manage_promotions"),
            Map.entry("remove_items_from_promotion", "manage_promotions"),
            Map.entry("analyze_item_competition", "concorrencia"),
            Map.entry("get_competition_report", "concorrencia"),
            Map.entry("get_category_discovery", "concorrencia"),
            Map.entry("inspect_listing", "concorrencia"));

    private static final ArrayNode TOOL_DEFINITIONS = parseDefinitions();

    private final MeliAuthService auth;
    private final DashboardService dashboard;
    private final MeliBulkService bulk;
    private final MeliQuestionsService questions;
    private final MeliPromotionsService promotions;
    private final OperationLogService logs;
    private final MeliCompetitionService competition;

    public AiToolRegistry(MeliAuthService auth, DashboardService dashboard, MeliBulkService bulk,
                          MeliQuestionsService questions, MeliPromotionsService promotions,
                          OperationLogService logs, MeliCompetitionService competition) {
        this.auth = auth;
        this.dashboard = dashboard;
        this.bulk = bulk;
        this.questions = questions;
        this.promotions = promotions;
        this.logs = logs;
        this.competition = competition;
    }

    public boolean isWriteTool(String name) { return WRITE_TOOLS.contains(name); }

    /** Definições filtradas: tool com permissão mapeada só aparece pra quem tem. */
    public ArrayNode toolDefinitions(AppUser user) {
        ArrayNode out = MAPPER.createArrayNode();
        for (JsonNode tool : TOOL_DEFINITIONS) {
            String name = tool.path("function").path("name").asText();
            String required = TOOL_PERMISSION.get(name);
            if (required == null || user.isAdmin() || user.getPermissions().contains(required)) {
                out.add(tool);
            }
        }
        return out;
    }

    /** Catálogo administrativo somente leitura das ferramentas reais do agente. */
    public List<Map<String, Object>> adminCatalog() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (JsonNode tool : TOOL_DEFINITIONS) {
            JsonNode function = tool.path("function");
            String name = function.path("name").asText();
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("name", name);
            item.put("description", function.path("description").asText(""));
            item.put("write", isWriteTool(name));
            item.put("permission", TOOL_PERMISSION.get(name));
            item.put("parameters", function.path("parameters"));
            out.add(item);
        }
        return out;
    }

    /** Executa tool de leitura; retorno já serializado e truncado (vai como tool result). */
    public String executeRead(String name, JsonNode args) {
        Object result = switch (name) {
            case "list_accounts" -> auth.listAccounts();
            case "get_dashboard_summary" -> dashboard.summary("all"); // "all" = todas as contas; null quebraria o split
            case "get_dashboard_performance" ->
                    dashboard.performance(dashboard.resolveRange(clamp(args.path("days").asInt(30), 1, 90), null, null));
            case "get_dashboard_revenue" ->
                    dashboard.revenue(dashboard.resolveRange(clamp(args.path("days").asInt(30), 1, 90), null, null));
            case "list_skus" -> {
                List<Map<String, Object>> skus = bulk.getSkusAllAccounts();
                yield skus.size() > MAX_SKUS ? skus.subList(0, MAX_SKUS) : skus;
            }
            case "get_items_by_sku" -> slimItemGroups(bulk.getItemsBySkuAllAccounts(requireText(args, "sku")));
            case "get_item_pictures" -> bulk.getItemPictures(
                    requireLong(args, "account_user_id"), requireText(args, "item_id"));
            case "list_questions" -> questions.listQuestions(args.path("status").asText("UNANSWERED"), 50, 7);
            case "questions_stats" -> questions.stats(args.path("period").asText("day"),
                    clamp(args.path("periods").asInt(30), 3, 60));
            case "list_promotions" -> promotions.listPromotions(requireLong(args, "account_user_id"));
            case "list_promotion_items" -> promotions.listPromotionItems(
                    requireLong(args, "account_user_id"),
                    requireText(args, "promotion_id"),
                    requireText(args, "promotion_type"),
                    args.path("status").asText(null), null);
            case "get_operation_logs" -> logs.listOperations(null, null, null, null, null,
                    0, clamp(args.path("limit").asInt(20), 1, 50));
            case "analyze_item_competition" -> competition.analyzeItem(
                    requireLong(args, "account_user_id"), requireText(args, "item_id"),
                    args.path("code").asText(null));
            case "get_competition_report" -> competition.agentReport(
                    requireLong(args, "account_user_id"), args.path("status").asText(null),
                    clamp(args.path("limit").asInt(20), 1, 50));
            case "get_category_discovery" -> competition.categoryDiscovery(
                    requireLong(args, "account_user_id"), requireText(args, "category_id"));
            case "inspect_listing" -> competition.inspectListing(
                    requireLong(args, "account_user_id"), requireText(args, "item_id"));
            default -> throw new IllegalArgumentException("Tool desconhecida: " + name);
        };
        return toJsonTruncated(result);
    }

    /** Permissão do painel exigida pra confirmar a tool de escrita. */
    public String writePermission(String name) {
        String perm = TOOL_PERMISSION.get(name);
        if (perm == null || !isWriteTool(name)) {
            throw new IllegalArgumentException("Tool de escrita desconhecida: " + name);
        }
        return perm;
    }

    /**
     * Resumo humano pro card de confirmação. Deriva do MESMO parsing que
     * executeWrite usa — o que o usuário confirma é exatamente o que executa.
     */
    public String summarize(String name, JsonNode args) {
        return switch (name) {
            case "bulk_update_items" -> {
                List<Map<String, Object>> groups = parseGroups(args.path("groups"));
                int items = 0;
                for (Map<String, Object> group : groups) {
                    items += ((List<?>) group.get("item_ids")).size();
                }
                ObjectNode updates = sanitizeUpdates(args.path("updates"));
                StringBuilder fields = new StringBuilder();
                updates.fields().forEachRemaining(e -> {
                    if (!fields.isEmpty()) fields.append(", ");
                    JsonNode v = e.getValue();
                    if ("pictures".equals(e.getKey())) {
                        // JSON cru das fotos poluiria o card — só a contagem importa
                        fields.append("fotos → ").append(v.size()).append(" imagem(ns)");
                    } else {
                        // asText() de objeto/array é "" — mostra o JSON pra não sumir do card
                        fields.append(e.getKey()).append(" → ")
                              .append(v.isValueNode() ? v.asText() : v.toString());
                    }
                });
                yield "Alterar " + items + " anúncio(s) em " + groups.size() + " conta(s): " + fields;
            }
            case "add_items_to_promotion" -> "Incluir " + parsePromotionItems(args).size()
                    + " anúncio(s) na promoção " + requireText(args, "promotion_id")
                    + " (" + requireText(args, "promotion_type") + ") da conta "
                    + requireLong(args, "account_user_id");
            case "remove_items_from_promotion" -> "Remover " + parseItemIds(args).size()
                    + " anúncio(s) da promoção " + requireText(args, "promotion_id")
                    + " (" + requireText(args, "promotion_type") + ") da conta "
                    + requireLong(args, "account_user_id");
            default -> throw new IllegalArgumentException("Tool de escrita desconhecida: " + name);
        };
    }

    /** Executa a escrita — chamado SÓ pelo confirm do AiController. */
    public Map<String, Object> executeWrite(String name, JsonNode args) {
        return switch (name) {
            case "bulk_update_items" -> bulk.bulkUpdateMultiAccount(
                    parseGroups(args.path("groups")),
                    sanitizeUpdates(args.path("updates")),
                    args.path("sku").asText(null), null, null, null);
            case "add_items_to_promotion" -> promotions.addItems(
                    requireLong(args, "account_user_id"),
                    requireText(args, "promotion_id"),
                    requireText(args, "promotion_type"),
                    parsePromotionItems(args));
            case "remove_items_from_promotion" -> promotions.removeItems(
                    requireLong(args, "account_user_id"),
                    requireText(args, "promotion_id"),
                    requireText(args, "promotion_type"),
                    parseItemIds(args));
            default -> throw new IllegalArgumentException("Tool de escrita desconhecida: " + name);
        };
    }

    /** Itens válidos de add_items_to_promotion (precisam de item_id). */
    private static List<JsonNode> parsePromotionItems(JsonNode args) {
        List<JsonNode> items = new java.util.ArrayList<>();
        for (JsonNode item : args.path("items")) {
            if (item.hasNonNull("item_id")) items.add(item);
        }
        if (items.isEmpty()) throw new IllegalArgumentException("items vazio");
        return items;
    }

    /** item_ids válidos de remove_items_from_promotion. */
    private static List<String> parseItemIds(JsonNode args) {
        List<String> itemIds = new java.util.ArrayList<>();
        for (JsonNode id : args.path("item_ids")) {
            if (!id.asText("").isBlank()) itemIds.add(id.asText());
        }
        if (itemIds.isEmpty()) throw new IllegalArgumentException("item_ids vazio");
        return itemIds;
    }

    private static final Set<String> ALLOWED_UPDATE_FIELDS =
            Set.of("price", "available_quantity", "status", "title", "pictures", "keep_cover_photo");

    private static ObjectNode sanitizeUpdates(JsonNode updates) {
        ObjectNode out = MAPPER.createObjectNode();
        if (updates != null && updates.isObject()) {
            updates.fields().forEachRemaining(e -> {
                if (ALLOWED_UPDATE_FIELDS.contains(e.getKey())) out.set(e.getKey(), e.getValue());
            });
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException(
                    "updates sem campos válidos (price, available_quantity, status, title, pictures)");
        }
        return out;
    }

    private static List<Map<String, Object>> parseGroups(JsonNode groups) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (JsonNode g : groups) {
            long userId = g.path("user_id").asLong(0);
            List<String> itemIds = new java.util.ArrayList<>();
            for (JsonNode id : g.path("item_ids")) itemIds.add(id.asText());
            if (userId == 0 || itemIds.isEmpty()) continue;
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("user_id", userId);
            m.put("item_ids", itemIds);
            out.add(m);
        }
        if (out.isEmpty()) throw new IllegalArgumentException("groups vazio ou inválido");
        return out;
    }

    /**
     * Só os campos que a descrição da tool promete. O payload completo do Meli
     * (pictures, attributes, variations…) estoura MAX_RESULT_CHARS e o truncamento
     * cortava a lista no meio — o modelo via 2 itens de um SKU com 10.
     */
    private static List<Map<String, Object>> slimItemGroups(List<Map<String, Object>> groups) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map<String, Object> group : groups) {
            Map<String, Object> g = new java.util.LinkedHashMap<>(group);
            List<Map<String, Object>> slim = new java.util.ArrayList<>();
            if (g.get("items") instanceof List<?> items) {
                for (Object o : items) {
                    if (o instanceof JsonNode item) {
                        Map<String, Object> m = new java.util.LinkedHashMap<>();
                        m.put("id", item.path("id").asText());
                        m.put("title", item.path("title").asText());
                        m.put("price", item.path("price").asDouble());
                        m.put("available_quantity", item.path("available_quantity").asInt());
                        m.put("sold_quantity", item.path("sold_quantity").asInt());
                        m.put("status", item.path("status").asText());
                        slim.add(m);
                    }
                }
            }
            g.put("items", slim);
            out.add(g);
        }
        return out;
    }

    // ---- Helpers -------------------------------------------------------------

    static String requireText(JsonNode args, String field) {
        String v = args.path(field).asText("");
        if (v.isBlank()) throw new IllegalArgumentException("argumento obrigatório ausente: " + field);
        return v;
    }

    static long requireLong(JsonNode args, String field) {
        long v = args.path(field).asLong(0);
        if (v == 0) throw new IllegalArgumentException("argumento obrigatório ausente: " + field);
        return v;
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(v, max)); }

    private static String toJsonTruncated(Object result) {
        try {
            String json = MAPPER.writeValueAsString(result);
            if (json.length() <= MAX_RESULT_CHARS) return json;
            return "{\"truncated\":true,\"data\":"
                    + MAPPER.writeValueAsString(json.substring(0, MAX_RESULT_CHARS)) + "}";
        } catch (Exception e) {
            return "{\"error\":\"falha ao serializar resultado\"}";
        }
    }

    private static ArrayNode parseDefinitions() {
        try {
            return (ArrayNode) MAPPER.readTree(TOOLS_JSON);
        } catch (Exception e) {
            throw new IllegalStateException("TOOLS_JSON inválido", e);
        }
    }

    // Schemas no formato OpenAI. Descrições em PT — o modelo escolhe melhor a
    // tool quando a descrição fala a língua do usuário.
    private static final String TOOLS_JSON = """
    [
      {"type":"function","function":{"name":"list_accounts","description":"Lista as contas Mercado Livre conectadas (user_id e nickname). Use antes de tools que pedem account_user_id.","parameters":{"type":"object","properties":{}}}},
      {"type":"function","function":{"name":"get_dashboard_summary","description":"Resumo geral do negócio: contas, anúncios ativos/pausados, reputação.","parameters":{"type":"object","properties":{}}}},
      {"type":"function","function":{"name":"get_dashboard_performance","description":"Vendas e visitas agregadas por dia no período, todas as contas.","parameters":{"type":"object","properties":{"days":{"type":"integer","description":"janela em dias (1-90, default 30)"}}}}},
      {"type":"function","function":{"name":"get_dashboard_revenue","description":"Faturamento por dia e por conta no período.","parameters":{"type":"object","properties":{"days":{"type":"integer","description":"janela em dias (1-90, default 30)"}}}}},
      {"type":"function","function":{"name":"list_skus","description":"SKUs de todas as contas com contagem de anúncios (máx. 100, ordenado por contagem).","parameters":{"type":"object","properties":{}}}},
      {"type":"function","function":{"name":"get_items_by_sku","description":"Anúncios de um SKU em todas as contas: id, título, preço, estoque, status, vendidos.","parameters":{"type":"object","properties":{"sku":{"type":"string"}},"required":["sku"]}}},
      {"type":"function","function":{"name":"get_item_pictures","description":"Fotos atuais de um anúncio, em ordem (a primeira é a capa): id e url de cada foto. Use antes de alterar fotos.","parameters":{"type":"object","properties":{"account_user_id":{"type":"integer"},"item_id":{"type":"string"}},"required":["account_user_id","item_id"]}}},
      {"type":"function","function":{"name":"list_questions","description":"Perguntas de compradores de todas as contas.","parameters":{"type":"object","properties":{"status":{"type":"string","enum":["UNANSWERED","ANSWERED"],"description":"default UNANSWERED"}}}}},
      {"type":"function","function":{"name":"questions_stats","description":"Volume de perguntas por dia ou mês, por conta.","parameters":{"type":"object","properties":{"period":{"type":"string","enum":["day","month"]},"periods":{"type":"integer","description":"3-60"}}}}},
      {"type":"function","function":{"name":"list_promotions","description":"Promoções/campanhas de uma conta.","parameters":{"type":"object","properties":{"account_user_id":{"type":"integer"}},"required":["account_user_id"]}}},
      {"type":"function","function":{"name":"list_promotion_items","description":"Uma página de anúncios de uma promoção (candidate = elegíveis, started = participando).","parameters":{"type":"object","properties":{"account_user_id":{"type":"integer"},"promotion_id":{"type":"string"},"promotion_type":{"type":"string"},"status":{"type":"string","enum":["candidate","started"]}},"required":["account_user_id","promotion_id","promotion_type"]}}},
      {"type":"function","function":{"name":"get_operation_logs","description":"Últimas operações feitas no painel (auditoria).","parameters":{"type":"object","properties":{"limit":{"type":"integer","description":"1-50, default 20"}}}}},
      {"type":"function","function":{"name":"analyze_item_competition","description":"Compara UM anúncio nosso com os concorrentes. Catálogo: quem ganha o buy box, minha posição, preço do vencedor, diferença, preço para ganhar e a lista de ofertas rivais (preço/frete/vendas). Avulso: acha concorrentes por código (OEM/PART_NUMBER/GTIN) ou título e devolve minha posição de preço, mediana, mais barato e a lista. A resposta traz o category_id (use em get_category_discovery) e os item_id dos rivais (use em inspect_listing).","parameters":{"type":"object","properties":{"account_user_id":{"type":"integer","description":"conta dona do anúncio (de list_accounts ou get_items_by_sku)"},"item_id":{"type":"string"},"code":{"type":"string","description":"opcional: força a busca de concorrentes por um código específico (ex.: código original OEM)"}},"required":["account_user_id","item_id"]}}},
      {"type":"function","function":{"name":"get_competition_report","description":"Panorama por conta da última varredura de concorrência: resumo (quantos ganhando/perdendo o buy box) e os anúncios ordenados pela MAIOR perda (maior diferença de preço vs vencedor). Use para achar onde estamos perdendo mais. Se não houver varredura, avisa para o usuário rodar 'Atualizar' na página Concorrência.","parameters":{"type":"object","properties":{"account_user_id":{"type":"integer"},"status":{"type":"string","enum":["competing","winning","sharing","not_listed"],"description":"filtra por situação; competing = perdendo o buy box"},"limit":{"type":"integer","description":"1-50, default 20"}},"required":["account_user_id"]}}},
      {"type":"function","function":{"name":"get_category_discovery","description":"Contexto de mercado de uma categoria: mais vendidos (ranking com item_id/preço/vendas) e termos de busca em alta. Pegue os item_id dos mais vendidos e chame inspect_listing para descobrir o que os líderes fazem diferente.","parameters":{"type":"object","properties":{"account_user_id":{"type":"integer"},"category_id":{"type":"string"}},"required":["account_user_id","category_id"]}}},
      {"type":"function","function":{"name":"inspect_listing","description":"Detalhes de QUALQUER anúncio (nosso ou de concorrente) para comparar o que muda: preço, vendas, tipo de anúncio (clássico/premium), frete grátis, garantia, nº de fotos, atributos preenchidos e trecho da descrição. Use nos rivais vencedores para explicar a performance e sugerir melhorias no nosso. Precisa de account_user_id (uma conta nossa, só para autenticar a leitura).","parameters":{"type":"object","properties":{"account_user_id":{"type":"integer","description":"qualquer conta nossa conectada, só para autenticar"},"item_id":{"type":"string"}},"required":["account_user_id","item_id"]}}},
      {"type":"function","function":{"name":"bulk_update_items","description":"ALTERAÇÃO em massa de anúncios (preço, estoque, status active/paused, título). NÃO executa: gera um pedido de confirmação pro usuário.","parameters":{"type":"object","properties":{"groups":{"type":"array","items":{"type":"object","properties":{"user_id":{"type":"integer"},"item_ids":{"type":"array","items":{"type":"string"}}},"required":["user_id","item_ids"]}},"updates":{"type":"object","properties":{"price":{"type":"number"},"available_quantity":{"type":"integer"},"status":{"type":"string","enum":["active","paused"]},"title":{"type":"string"},"pictures":{"type":"array","description":"substitui TODAS as fotos, na ordem enviada (a primeira vira capa); cada item é {\\"source\\":\\"url da imagem\\"} para foto nova ou {\\"id\\":\\"id de foto existente\\"} para manter/reordenar","items":{"type":"object"}},"keep_cover_photo":{"type":"boolean","description":"true mantém a capa atual de cada anúncio e insere as fotos enviadas depois dela"}}},"sku":{"type":"string","description":"SKU de referência, só pro histórico"}},"required":["groups","updates"]}}},
      {"type":"function","function":{"name":"add_items_to_promotion","description":"ALTERAÇÃO: inclui anúncios numa promoção (deal_price obrigatório em promoções tipo DEAL/LIGHTNING). Gera confirmação.","parameters":{"type":"object","properties":{"account_user_id":{"type":"integer"},"promotion_id":{"type":"string"},"promotion_type":{"type":"string"},"items":{"type":"array","items":{"type":"object","properties":{"item_id":{"type":"string"},"deal_price":{"type":"number"}},"required":["item_id"]}}},"required":["account_user_id","promotion_id","promotion_type","items"]}}},
      {"type":"function","function":{"name":"remove_items_from_promotion","description":"ALTERAÇÃO: remove anúncios de uma promoção. Gera confirmação.","parameters":{"type":"object","properties":{"account_user_id":{"type":"integer"},"promotion_id":{"type":"string"},"promotion_type":{"type":"string"},"item_ids":{"type":"array","items":{"type":"string"}}},"required":["account_user_id","promotion_id","promotion_type","item_ids"]}}}
    ]
    """;
}
