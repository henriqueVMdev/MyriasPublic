package com.myrias.mlmanager.meli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ItemPositionTest {

    @Test
    void resolvePeloNomeToleraGenero() {
        assertEquals(ItemPosition.DIANTEIRA, ItemPosition.resolve(Map.of("value_name", "Dianteiro")));
        assertEquals(ItemPosition.DIANTEIRA, ItemPosition.resolve(Map.of("value_name", " Dianteira ")));
        assertEquals(ItemPosition.DIREITA, ItemPosition.resolve(Map.of("value_name", "DIREITO")));
    }

    /** Era o que a cópia do MeliCloneService não fazia: sem nome, cai no id. */
    @Test
    void resolveCaiNoValueIdQuandoONomeNaoDiz() {
        assertEquals(ItemPosition.TRASEIRA,
                ItemPosition.resolve(Map.of("value_id", "13701105", "value_name", "Rear")));
        assertNull(ItemPosition.resolve(Map.of("value_id", "999", "value_name", "Sei la")));
        assertNull(ItemPosition.resolve(Map.of()));
    }

    @Test
    void inferePeloTituloNaOrdemDaTabela() {
        assertEquals(List.of(ItemPosition.DIANTEIRA, ItemPosition.DIREITA),
                ItemPosition.fromTitle("Farol Dianteiro Direito Gol G5"));
        assertEquals(List.of(ItemPosition.TRASEIRA, ItemPosition.ESQUERDA),
                ItemPosition.fromTitle("Lanterna Tras. LH"));
        // "direita" só vale como palavra: "direitamente" não é posição.
        assertEquals(List.of(), ItemPosition.fromTitle("Kit reparo direitamente do fabricante"));
    }

    @Test
    void asValueUsaAsChavesQueOMlEspera() {
        assertEquals(Map.of("value_id", "2262158", "value_name", "Esquerda"),
                ItemPosition.ESQUERDA.asValue());
    }
}
