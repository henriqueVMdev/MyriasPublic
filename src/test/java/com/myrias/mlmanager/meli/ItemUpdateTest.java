package com.myrias.mlmanager.meli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/**
 * Garante o equivalente ao model_dump(exclude_none=True): só campos preenchidos
 * vão no JSON enviado ao ML. Se nulls vazassem, o PUT /items zeraria campos do
 * anúncio — caminho de perda de dados.
 */
class ItemUpdateTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializesOnlySetFieldsInSnakeCase() {
        ItemUpdate u = new ItemUpdate();
        u.price = 199.9;
        u.availableQuantity = 3;

        ObjectNode node = mapper.valueToTree(u);

        assertTrue(node.has("price"), "price deve aparecer");
        assertTrue(node.has("available_quantity"), "deve usar snake_case");
        assertEquals(2, node.size(), "nenhum campo nulo deve ir no payload");
        assertFalse(node.has("title"), "campo não setado não pode aparecer");
        assertFalse(node.has("status"), "campo não setado não pode aparecer");
    }
}
