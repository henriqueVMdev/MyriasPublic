package com.myrias.mlmanager.meli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myrias.mlmanager.meli.MeliClient.MeliResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class MeliClientMultiGetTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void retornaCorposBemSucedidosSemFallback() throws Exception {
        StubClient client = new StubClient(response(200, """
                [
                  {
                    "code": 200,
                    "body": {
                      "id": "MLB1",
                      "title": "Item 1",
                      "attributes": [{"id": "BRAND", "value_name": "Marca"}]
                    }
                  }
                ]
                """));
        try {
            List<JsonNode> items = client.multiGetItems(
                    List.of("MLB1"), List.of("id", "title", "attributes"), null);

            assertEquals(1, items.size());
            assertEquals("MLB1", items.getFirst().path("id").asText());
            assertTrue(client.individualCalls.isEmpty());
        } finally {
            client.close();
        }
    }

    @Test
    void usaFallbackIndividualQuandoMultigetRejeitaItem() throws Exception {
        StubClient client = new StubClient(response(200, """
                [
                  {
                    "code": 403,
                    "body": {"message": "blocked by policy"}
                  }
                ]
                """));
        client.individualResponses.put("MLB1", response(200, """
                {
                  "id": "MLB1",
                  "title": "Recuperado",
                  "attributes": [{"id": "BRAND"}]
                }
                """));

        try {
            List<JsonNode> items = client.multiGetItems(
                    List.of("MLB1"), List.of("id", "title", "attributes"), null);

            assertEquals(1, items.size());
            assertEquals("Recuperado", items.getFirst().path("title").asText());
            assertEquals(List.of("MLB1"), client.individualCalls);
            assertEquals("all", client.individualParams.get("MLB1").get("include_attributes"));
        } finally {
            client.close();
        }
    }

    @Test
    void refazConsultaQuandoMultigetOmiteAttributes() throws Exception {
        StubClient client = new StubClient(response(200, """
                [
                  {
                    "code": 200,
                    "body": {"id": "MLB1", "title": "Parcial"}
                  }
                ]
                """));
        client.individualResponses.put("MLB1", response(200, """
                {
                  "id": "MLB1",
                  "title": "Completo",
                  "attributes": [{"id": "MODEL", "value_name": "X"}]
                }
                """));

        try {
            List<JsonNode> items = client.multiGetItems(
                    List.of("MLB1"), List.of("id", "title", "attributes"), null);

            assertEquals(1, items.size());
            assertEquals("Completo", items.getFirst().path("title").asText());
            assertEquals(1, items.getFirst().path("attributes").size());
        } finally {
            client.close();
        }
    }

    @Test
    void mantemCorpoParcialSeRefetchDeAttributesFalhar() throws Exception {
        StubClient client = new StubClient(response(200, """
                [
                  {
                    "code": 200,
                    "body": {"id": "MLB1", "title": "Parcial"}
                  }
                ]
                """));
        client.individualResponses.put("MLB1",
                response(403, "{\"message\": \"blocked by policy\"}"));

        try {
            List<JsonNode> items = client.multiGetItems(
                    List.of("MLB1"), List.of("id", "title", "attributes"), null);

            assertEquals(1, items.size());
            assertEquals("Parcial", items.getFirst().path("title").asText());
        } finally {
            client.close();
        }
    }

    private static MeliResponse response(int status, String json) throws Exception {
        return new MeliResponse(status, MAPPER.readTree(json));
    }

    private static final class StubClient extends MeliClient {
        private final MeliResponse multiResponse;
        private final Map<String, MeliResponse> individualResponses = new ConcurrentHashMap<>();
        private final List<String> individualCalls = new ArrayList<>();
        private final Map<String, Map<String, String>> individualParams = new ConcurrentHashMap<>();

        StubClient(MeliResponse multiResponse) {
            super(mock(MeliAuthService.class), new MeliRateLimiter(60_000, 10), 1);
            this.multiResponse = multiResponse;
        }

        @Override
        public MeliResponse get(String path, Map<String, String> params, Long userId) {
            if ("/items".equals(path)) return multiResponse;
            String itemId = itemId(path);
            individualCalls.add(itemId);
            individualParams.put(itemId, params);
            return individualResponses.getOrDefault(itemId,
                    new MeliResponse(404, MAPPER.createObjectNode().put("message", "not found")));
        }

        @Override
        public MeliResponse get(String path, Long userId) {
            String itemId = itemId(path);
            individualCalls.add(itemId);
            return individualResponses.getOrDefault(itemId,
                    new MeliResponse(404, MAPPER.createObjectNode().put("message", "not found")));
        }

        private static String itemId(String path) {
            return path.substring(path.lastIndexOf('/') + 1);
        }
    }
}
