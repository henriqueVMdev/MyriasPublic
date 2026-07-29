package com.hrb.mlmanager.meli;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrb.mlmanager.ops.OperationLogRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class MeliBulkSkuSearchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode results(String... ids) {
        try {
            return MAPPER.readTree("{\"results\":"
                    + MAPPER.writeValueAsString(List.of(ids)) + "}");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * seller_sku indexa o seller_custom_field e sku o atributo SELLER_SKU.
     * O fallback antigo só consultava o segundo se o primeiro viesse vazio —
     * um SKU com 10 anúncios divididos entre os índices voltava incompleto.
     */
    @Test
    void uneAsDuasBuscasDeSkuSemDuplicar() {
        MeliClient client = mock(MeliClient.class);
        MeliBulkService service = new MeliBulkService(client,
                mock(MeliAuthService.class), mock(OperationLogRepository.class));

        when(client.get(eq("/users/1/items/search"),
                argThat(m -> m.containsKey("seller_sku")), eq(1L)))
                .thenReturn(new MeliClient.MeliResponse(200, results("MLB1", "MLB2")));
        when(client.get(eq("/users/1/items/search"),
                argThat(m -> m.containsKey("sku")), eq(1L)))
                .thenReturn(new MeliClient.MeliResponse(200, results("MLB2", "MLB3", "MLB4")));
        when(client.multiGetItems(anyList(), anyList(), eq(1L))).thenReturn(List.of());

        service.getItemsBySku(1L, "444");

        verify(client).multiGetItems(
                eq(List.of("MLB1", "MLB2", "MLB3", "MLB4")), anyList(), eq(1L));
    }
}
