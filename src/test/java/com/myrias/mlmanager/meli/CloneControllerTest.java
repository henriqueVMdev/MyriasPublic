package com.myrias.mlmanager.meli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.myrias.mlmanager.auth.PanelSecurity;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class CloneControllerTest {

    @Test
    void previewDevolveMensagemDaFalhaNoCorpo() {
        MeliCloneService service = mock(MeliCloneService.class);
        when(service.preview("MLBU1975747175"))
                .thenThrow(new IllegalStateException("Nenhuma conta do Mercado Livre esta conectada."));

        CloneController controller = new CloneController(
                service,
                mock(MeliAuthService.class),
                mock(PanelSecurity.class)
        );

        ResponseEntity<?> response = controller.preview(
                new CloneController.PreviewRequest("MLBU1975747175"));

        assertEquals(400, response.getStatusCode().value());
        assertTrue(response.getBody() instanceof Map);
        assertEquals(
                "Nenhuma conta do Mercado Livre esta conectada.",
                ((Map<?, ?>) response.getBody()).get("detail")
        );
    }
}
