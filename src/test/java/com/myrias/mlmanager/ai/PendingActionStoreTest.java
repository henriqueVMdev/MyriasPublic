package com.hrb.mlmanager.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class PendingActionStoreTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void createEConsumeDevolvemAMesmaAcao() {
        PendingActionStore store = new PendingActionStore(Duration.ofMinutes(10));
        var action = store.create(1L, "bulk_update_items", MAPPER.createObjectNode(), "Alterar 2 anúncios");
        var consumed = store.consume(action.id(), 1L);
        assertNotNull(consumed);
        assertEquals("bulk_update_items", consumed.tool());
        assertNull(store.consume(action.id(), 1L)); // segunda vez: já consumida
    }

    @Test
    void usuarioDiferenteNaoConsome() {
        PendingActionStore store = new PendingActionStore(Duration.ofMinutes(10));
        var action = store.create(1L, "add_items_to_promotion", MAPPER.createObjectNode(), "x");
        assertNull(store.consume(action.id(), 2L));
    }

    @Test
    void acaoExpiradaNaoConsome() throws InterruptedException {
        PendingActionStore store = new PendingActionStore(Duration.ofMillis(1));
        var action = store.create(1L, "bulk_update_items", MAPPER.createObjectNode(), "x");
        Thread.sleep(10);
        assertNull(store.consume(action.id(), 1L));
    }

    @Test
    void discardRemove() {
        PendingActionStore store = new PendingActionStore(Duration.ofMinutes(10));
        var action = store.create(1L, "bulk_update_items", MAPPER.createObjectNode(), "x");
        store.discard(action.id(), 1L);
        assertNull(store.consume(action.id(), 1L));
    }

    @Test
    void tentativaDeOutroUsuarioNaoDestroiAcaoDoDono() {
        PendingActionStore store = new PendingActionStore(Duration.ofMinutes(10));
        var action = store.create(1L, "bulk_update_items", MAPPER.createObjectNode(), "x");
        assertNull(store.consume(action.id(), 2L));          // usuário errado
        assertNotNull(store.consume(action.id(), 1L));       // dono ainda consegue
    }

    @Test
    void idInexistenteDevolveNull() {
        PendingActionStore store = new PendingActionStore(Duration.ofMinutes(10));
        assertNull(store.consume("nao-existe", 1L));
    }
}
