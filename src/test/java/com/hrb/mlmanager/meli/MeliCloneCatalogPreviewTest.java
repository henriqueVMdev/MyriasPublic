package com.hrb.mlmanager.meli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrb.mlmanager.meli.MeliClient.MeliResponse;
import com.hrb.mlmanager.ops.OperationLogRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MeliCloneCatalogPreviewTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void montaPreviewParcialDeTerceiroUsandoOfertasDoCatalogo() throws Exception {
        StubClient client = new StubClient();
        MeliAuthService auth = mock(MeliAuthService.class);
        when(auth.listAccounts()).thenReturn(List.of(Map.of(
                "user_id", 442659610L,
                "nickname", "CONTA_TESTE",
                "is_active", true
        )));

        MeliCloneService service = new MeliCloneService(
                client,
                auth,
                mock(MeliBulkService.class),
                mock(OperationLogRepository.class)
        );

        try {
            Map<String, Object> preview = service.preview(
                    "https://www.mercadolivre.com.br/noindex/up/MLBU1234567890");

            Map<?, ?> original = (Map<?, ?>) preview.get("original");
            Map<?, ?> suggested = (Map<?, ?>) preview.get("suggested");
            List<?> attributes = (List<?>) suggested.get("attributes");
            List<?> pictures = (List<?>) suggested.get("pictures");

            assertEquals("MLBU1234567890", original.get("id"));
            assertEquals("MLB-CAT-TEST", suggested.get("category_id"));
            assertEquals(159.9, ((Number) suggested.get("price")).doubleValue(), 0.001);
            assertEquals("", suggested.get("title"));
            assertTrue(pictures.isEmpty());
            assertTrue(attributes.stream().anyMatch(a ->
                    "BRAND".equals(((Map<?, ?>) a).get("id"))));
            assertTrue(attributes.stream().anyMatch(a ->
                    "SELLER_PACKAGE_HEIGHT".equals(((Map<?, ?>) a).get("id"))));
        } finally {
            client.close();
        }
    }

    private static final class StubClient extends MeliClient {
        StubClient() {
            super(mock(MeliAuthService.class), new MeliRateLimiter(60_000, 10), 1);
        }

        @Override
        public MeliResponse get(String path, Map<String, String> params, Long userId) {
            if ("/products/MLBU1234567890/items".equals(path)) {
                return response(200, """
                        {
                          "results": [
                            {
                              "category_id": "MLB-CAT-TEST",
                              "price": 159.9,
                              "condition": "new",
                              "currency_id": "BRL",
                              "user_product_id": "MLBU9999999999"
                            }
                          ]
                        }
                        """);
            }
            return response(404, "{\"message\":\"not found\"}");
        }

        @Override
        public MeliResponse get(String path, Long userId) {
            if (path.startsWith("/user-products/")) {
                return response(403, "{\"message\":\"blocked by policy\"}");
            }
            return response(404, "{\"message\":\"not found\"}");
        }

        @Override
        public MeliResponse getPublic(String path) {
            if ("/categories/MLB-CAT-TEST/attributes".equals(path)) {
                return response(200, """
                        [
                          {
                            "id": "BRAND",
                            "name": "Marca",
                            "value_type": "string",
                            "tags": {}
                          },
                          {
                            "id": "MODEL",
                            "name": "Modelo",
                            "value_type": "string",
                            "tags": {}
                          },
                          {
                            "id": "READ_ONLY",
                            "name": "Somente leitura",
                            "tags": {"read_only": true}
                          },
                          {
                            "id": "PACKAGE_HEIGHT",
                            "name": "Altura",
                            "tags": {}
                          }
                        ]
                        """);
            }
            return response(404, "{\"message\":\"not found\"}");
        }

        private static MeliResponse response(int status, String json) {
            try {
                JsonNode data = MAPPER.readTree(json);
                return new MeliResponse(status, data);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
