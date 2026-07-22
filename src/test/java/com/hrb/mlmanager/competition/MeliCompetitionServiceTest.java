package com.hrb.mlmanager.competition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/** Cálculo puro de posição/gap/preço-para-ganhar a partir das ofertas de catálogo (sem rede). */
class MeliCompetitionServiceTest {

    private final ObjectMapper m = new ObjectMapper();

    private ObjectNode offer(long sellerId, String itemId, double price) {
        ObjectNode o = m.createObjectNode();
        o.put("seller_id", sellerId);
        o.put("item_id", itemId);
        o.put("price", price);
        return o;
    }

    @Test
    void competingSellerGetsPositionGapAndPriceToWin() {
        long me = 111L;
        ArrayNode offers = m.createArrayNode();
        offers.add(offer(999L, "MLB-A", 100.0)); // concorrente mais barato = ganhador
        offers.add(offer(me, "MLB-MINE", 130.0)); // eu, mais caro
        offers.add(offer(888L, "MLB-B", 150.0));

        ObjectNode prod = m.createObjectNode();
        prod.putObject("buy_box_winner").put("item_id", "MLB-A").put("price", 100.0);

        ObjectNode r = MeliCompetitionService.analyzeCatalogOffers(prod, offers, me, "MLB-MINE", 130.0);

        assertEquals("competing", r.path("status").asText());
        assertEquals(3, r.path("competitor_count").asInt());
        assertEquals(2, r.path("my_position").asInt(), "sou o 2º mais barato");
        assertEquals(100.0, r.path("winner_price").asDouble(), 0.001);
        assertEquals(30.0, r.path("price_gap").asDouble(), 0.001);
        assertEquals(30.0, r.path("price_gap_pct").asDouble(), 0.001);
        assertEquals(99.99, r.path("price_to_win").asDouble(), 0.001);
    }

    @Test
    void winningSellerHasNoGap() {
        long me = 111L;
        ArrayNode offers = m.createArrayNode();
        offers.add(offer(me, "MLB-MINE", 90.0));
        offers.add(offer(999L, "MLB-A", 100.0));

        // Sem productNode: o ganhador vira a oferta mais barata (a minha).
        ObjectNode r = MeliCompetitionService.analyzeCatalogOffers(null, offers, me, "MLB-MINE", 90.0);

        assertEquals("winning", r.path("status").asText());
        assertEquals(1, r.path("my_position").asInt());
        assertTrue(r.path("price_gap").isNull(), "ganhador não tem gap");
        assertTrue(r.path("price_to_win").isNull());
    }

    @Test
    void notListedWhenSellerAbsent() {
        ArrayNode offers = m.createArrayNode();
        offers.add(offer(999L, "MLB-A", 100.0));
        offers.add(offer(888L, "MLB-B", 120.0));

        ObjectNode r = MeliCompetitionService.analyzeCatalogOffers(null, offers, 111L, "MLB-MINE", 0.0);

        assertEquals("not_listed", r.path("status").asText());
        assertEquals(0, r.path("my_position").asInt());
    }

    private ObjectNode searchResult(long sellerId, String id, double price, boolean free) {
        ObjectNode o = m.createObjectNode();
        o.putObject("seller").put("id", sellerId).put("nickname", "s" + sellerId);
        o.put("id", id);
        o.put("price", price);
        o.putObject("shipping").put("free_shipping", free);
        return o;
    }

    @Test
    void standaloneSearchComputesPositionAndPercentile() {
        long me = 111L;
        ArrayNode results = m.createArrayNode();
        results.add(searchResult(me, "MLB-MINE", 130.0, false));  // meu próprio anúncio: ignorado
        results.add(searchResult(999L, "MLB-A", 100.0, true));
        results.add(searchResult(888L, "MLB-B", 150.0, false));
        results.add(searchResult(777L, "MLB-C", 200.0, true));

        ObjectNode r = MeliCompetitionService.analyzeSearchResults(results, me, "MLB-MINE", 130.0);

        assertEquals(3, r.path("competitor_count").asInt(), "meu anúncio não conta como concorrente");
        assertEquals("below_median", r.path("status").asText());
        assertEquals(2, r.path("my_position").asInt(), "1 concorrente (100) é mais barato que eu (130)");
        assertEquals(150.0, r.path("median_price").asDouble(), 0.001);
        assertEquals(100.0, r.path("min_price").asDouble(), 0.001);
        assertTrue(r.path("free_shipping_pct").asDouble() > 0);
    }

    @Test
    void standaloneCheapestWhenBelowAll() {
        ArrayNode results = m.createArrayNode();
        results.add(searchResult(999L, "MLB-A", 100.0, false));
        results.add(searchResult(888L, "MLB-B", 120.0, false));

        ObjectNode r = MeliCompetitionService.analyzeSearchResults(results, 111L, "MLB-MINE", 90.0);

        assertEquals("cheapest", r.path("status").asText());
        assertEquals(1, r.path("my_position").asInt());
    }

    private ObjectNode attr(String id, String valueName) {
        return m.createObjectNode().put("id", id).put("value_name", valueName);
    }

    @Test
    void extractCodesPrefersOemAndFiltersJunk() {
        ObjectNode item = m.createObjectNode();
        ArrayNode attrs = item.putArray("attributes");
        attrs.add(attr("BRAND", "Bosch"));                 // sem dígito → ignorado
        attrs.add(attr("OEM", "9L8Z-6079-A, 9L8Z6079A"));  // 2 tokens, ambos com dígito
        attrs.add(attr("PART_NUMBER", "BX123"));           // len>=4 com dígito

        var codes = MeliCompetitionService.extractCodes(item);

        assertTrue(codes.size() >= 2, "deve extrair códigos com dígito");
        assertEquals("9L8Z-6079-A", codes.get(0).value(), "OEM vem primeiro");
        assertTrue(codes.stream().noneMatch(c -> c.value().equals("Bosch")), "marca sem dígito não é código");
    }
}
