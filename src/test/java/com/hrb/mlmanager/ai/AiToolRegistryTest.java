package com.hrb.mlmanager.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hrb.mlmanager.auth.AppUser;
import com.hrb.mlmanager.dashboard.DashboardService;
import com.hrb.mlmanager.meli.MeliAuthService;
import com.hrb.mlmanager.meli.MeliBulkService;
import com.hrb.mlmanager.meli.MeliPromotionsService;
import com.hrb.mlmanager.meli.MeliQuestionsService;
import com.hrb.mlmanager.ops.OperationLogService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AiToolRegistryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MeliAuthService auth;
    private DashboardService dashboard;
    private MeliBulkService bulk;
    private MeliQuestionsService questions;
    private MeliPromotionsService promotions;
    private OperationLogService logs;
    private AiToolRegistry registry;

    @BeforeEach
    void setUp() {
        auth = mock(MeliAuthService.class);
        dashboard = mock(DashboardService.class);
        bulk = mock(MeliBulkService.class);
        questions = mock(MeliQuestionsService.class);
        promotions = mock(MeliPromotionsService.class);
        logs = mock(OperationLogService.class);
        registry = new AiToolRegistry(auth, dashboard, bulk, questions, promotions, logs);
    }

    private static AppUser userWith(boolean admin, String... perms) {
        AppUser user = mock(AppUser.class);
        when(user.isAdmin()).thenReturn(admin);
        when(user.getPermissions()).thenReturn(List.of(perms));
        return user;
    }

    private static List<String> toolNames(ArrayNode defs) {
        List<String> names = new ArrayList<>();
        defs.forEach(t -> names.add(t.path("function").path("name").asText()));
        return names;
    }

    @Test
    void listAccountsDespachaProService() {
        when(auth.listAccounts()).thenReturn(List.of(Map.of("user_id", 1L, "nickname", "LOJA")));
        String out = registry.executeRead("list_accounts", MAPPER.createObjectNode());
        assertTrue(out.contains("LOJA"));
        verify(auth).listAccounts();
    }

    @Test
    void getItemsBySkuPassaOArgumento() {
        when(bulk.getItemsBySkuAllAccounts("ABC-1")).thenReturn(List.of());
        ObjectNode args = MAPPER.createObjectNode().put("sku", "ABC-1");
        registry.executeRead("get_items_by_sku", args);
        verify(bulk).getItemsBySkuAllAccounts("ABC-1");
    }

    @Test
    void toolDesconhecidaLancaErro() {
        assertThrows(IllegalArgumentException.class,
                () -> registry.executeRead("tool_inexistente", MAPPER.createObjectNode()));
    }

    @Test
    void revenueSomeParaQuemNaoTemAMetrica() {
        List<String> semRevenue = toolNames(registry.toolDefinitions(userWith(false, "assistente")));
        assertFalse(semRevenue.contains("get_dashboard_revenue"));
        assertTrue(semRevenue.contains("get_dashboard_summary"));

        List<String> admin = toolNames(registry.toolDefinitions(userWith(true)));
        assertTrue(admin.contains("get_dashboard_revenue"));
    }

    @Test
    void toolsDeEscritaSomemSemAPermissaoDaAcao() {
        List<String> soLeitura = toolNames(registry.toolDefinitions(userWith(false, "assistente")));
        assertFalse(soLeitura.contains("bulk_update_items"));
        List<String> comBulk = toolNames(registry.toolDefinitions(userWith(false, "assistente", "bulk_edit")));
        assertTrue(comBulk.contains("bulk_update_items"));
        assertFalse(comBulk.contains("add_items_to_promotion"));
    }

    @Test
    void listSkusTruncaEm100() {
        List<Map<String, Object>> muitos = new ArrayList<>();
        for (int i = 0; i < 250; i++) muitos.add(Map.of("sku", "S" + i, "count", 1));
        when(bulk.getSkusAllAccounts()).thenReturn(muitos);
        String out = registry.executeRead("list_skus", MAPPER.createObjectNode());
        assertTrue(out.contains("S99"));
        assertFalse(out.contains("\"S100\""));
    }

    @Test
    void dashboardSummaryPassaAllEnaoNull() {
        when(dashboard.summary("all")).thenReturn(Map.of("ok", true));
        registry.executeRead("get_dashboard_summary", MAPPER.createObjectNode());
        verify(dashboard).summary("all");
    }

    @Test
    void executeReadRecusaToolsDeEscrita() {
        for (String write : AiToolRegistry.WRITE_TOOLS) {
            assertThrows(IllegalArgumentException.class,
                    () -> registry.executeRead(write, MAPPER.createObjectNode()));
        }
    }

    @Test
    void resultadoGrandeEhTruncado() {
        List<Map<String, Object>> grande = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            grande.add(Map.of("sku", "S" + i, "descricao", "x".repeat(500)));
        }
        when(bulk.getSkusAllAccounts()).thenReturn(grande);
        String out = registry.executeRead("list_skus", MAPPER.createObjectNode());
        assertTrue(out.startsWith("{\"truncated\":true"), out.substring(0, Math.min(60, out.length())));
        assertTrue(out.length() < 20000);
    }

    @Test
    void writePermissionMapeiaCerto() {
        assertEquals("bulk_edit", registry.writePermission("bulk_update_items"));
        assertEquals("manage_promotions", registry.writePermission("add_items_to_promotion"));
        assertEquals("manage_promotions", registry.writePermission("remove_items_from_promotion"));
    }

    @Test
    void summarizeBulkDescreveAAcao() throws Exception {
        var args = MAPPER.readTree("""
            {"groups":[{"user_id":1,"item_ids":["MLB1","MLB2"]},{"user_id":2,"item_ids":["MLB3"]}],
             "updates":{"price":44.9,"status":"paused"}}
            """);
        String s = registry.summarize("bulk_update_items", args);
        assertTrue(s.contains("3 anúncio(s)"), s);
        assertTrue(s.contains("2 conta(s)"), s);
        assertTrue(s.contains("price"), s);
    }

    @Test
    void executeWriteBulkChamaOServiceComGruposParseados() throws Exception {
        var args = MAPPER.readTree("""
            {"groups":[{"user_id":10,"item_ids":["MLB1"]}],"updates":{"price":9.9},"sku":"ABC"}
            """);
        when(bulk.bulkUpdateMultiAccount(anyList(), any(), eq("ABC"), isNull(), isNull(), isNull()))
                .thenReturn(Map.of("total", 1, "success", 1));
        Map<String, Object> out = registry.executeWrite("bulk_update_items", args);
        assertEquals(1, out.get("success"));
        verify(bulk).bulkUpdateMultiAccount(
                argThat(groups -> groups.size() == 1
                        && ((Number) groups.get(0).get("user_id")).longValue() == 10L),
                argThat(updates -> updates.has("price") && updates.size() == 1),
                eq("ABC"), isNull(), isNull(), isNull());
    }

    @Test
    void executeWriteRejeitaUpdatesForaDaWhitelist() throws Exception {
        var args = MAPPER.readTree("""
            {"groups":[{"user_id":1,"item_ids":["MLB1"]}],"updates":{"listing_type_id":"gold_pro"}}
            """);
        assertThrows(IllegalArgumentException.class,
                () -> registry.executeWrite("bulk_update_items", args));
    }

    @Test
    void executeWritePromocaoAddERemove() throws Exception {
        var addArgs = MAPPER.readTree("""
            {"account_user_id":10,"promotion_id":"P-1","promotion_type":"DEAL",
             "items":[{"item_id":"MLB1","deal_price":19.9}]}
            """);
        when(promotions.addItems(eq(10L), eq("P-1"), eq("DEAL"), anyList()))
                .thenReturn(Map.of("ok", true));
        registry.executeWrite("add_items_to_promotion", addArgs);
        verify(promotions).addItems(eq(10L), eq("P-1"), eq("DEAL"),
                argThat(items -> items.size() == 1 && items.get(0).path("item_id").asText().equals("MLB1")));

        var rmArgs = MAPPER.readTree("""
            {"account_user_id":10,"promotion_id":"P-1","promotion_type":"DEAL","item_ids":["MLB1","MLB2"]}
            """);
        when(promotions.removeItems(eq(10L), eq("P-1"), eq("DEAL"), anyList()))
                .thenReturn(Map.of("ok", true));
        registry.executeWrite("remove_items_from_promotion", rmArgs);
        verify(promotions).removeItems(10L, "P-1", "DEAL", List.of("MLB1", "MLB2"));
    }

    // O card de confirmação tem que prometer exatamente o que executeWrite faz:
    // grupos/itens descartados no parse não podem entrar na conta mostrada.

    @Test
    void summarizeContaSomenteGruposQueVaoExecutar() throws Exception {
        var args = MAPPER.readTree("""
            {"groups":[{"user_id":1,"item_ids":["MLB1","MLB2"]},{"user_id":0,"item_ids":["MLB3"]}],
             "updates":{"price":9.9}}
            """);
        String s = registry.summarize("bulk_update_items", args);
        assertTrue(s.contains("2 anúncio(s)"), s);
        assertTrue(s.contains("1 conta(s)"), s);
        assertFalse(s.contains("3 anúncio(s)"), s);
    }

    @Test
    void summarizeMostraSomenteCamposDaWhitelist() throws Exception {
        var args = MAPPER.readTree("""
            {"groups":[{"user_id":1,"item_ids":["MLB1"]}],
             "updates":{"price":9.9,"listing_type_id":"gold_pro"}}
            """);
        String s = registry.summarize("bulk_update_items", args);
        assertTrue(s.contains("price"), s);
        assertFalse(s.contains("listing_type_id"), s);
    }

    @Test
    void summarizePromocaoContaSomenteItensValidos() throws Exception {
        var args = MAPPER.readTree("""
            {"account_user_id":10,"promotion_id":"P-1","promotion_type":"DEAL",
             "items":[{"item_id":"MLB1"},{"deal_price":5.0}]}
            """);
        String s = registry.summarize("add_items_to_promotion", args);
        assertTrue(s.contains("1 anúncio(s)"), s);
    }

    @Test
    void summarizeMostraValorNaoEscalarComoJson() throws Exception {
        // asText() de objeto é "" — o card mostrava "price → " vazio.
        var args = MAPPER.readTree("""
            {"groups":[{"user_id":1,"item_ids":["MLB1"]}],
             "updates":{"price":{"value":9.9}}}
            """);
        String s = registry.summarize("bulk_update_items", args);
        assertTrue(s.contains("price → {\"value\":9.9}"), s);
    }

    @Test
    void writePermissionRecusaToolDeLeitura() {
        assertThrows(IllegalArgumentException.class,
                () -> registry.writePermission("get_dashboard_revenue"));
        assertThrows(IllegalArgumentException.class,
                () -> registry.writePermission("list_skus"));
    }
}
