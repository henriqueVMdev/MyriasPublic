package com.myrias.mlmanager.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AiModelSettingsServiceTest {

    private AiModelSettingRepository repository;
    private OpenRouterClient openRouter;
    private AiModelSettingsService service;

    @BeforeEach
    void setUp() {
        repository = mock(AiModelSettingRepository.class);
        openRouter = mock(OpenRouterClient.class);
        service = new AiModelSettingsService(repository, openRouter);
        when(openRouter.defaultModel()).thenReturn("modelo/a");
        when(openRouter.availableModels()).thenReturn(List.of(
                new OpenRouterClient.ModelOption("modelo/a", "Modelo A"),
                new OpenRouterClient.ModelOption("modelo/b", "Modelo B")
        ));
    }

    @Test
    void usaDefaultQuandoAindaNaoHaConfiguracaoSalva() {
        when(repository.findById(AiModelSetting.SINGLETON_ID)).thenReturn(Optional.empty());

        assertEquals("modelo/a", service.currentModel());
    }

    @Test
    void usaModeloPersistidoQuandoPermitido() {
        when(repository.findById(AiModelSetting.SINGLETON_ID))
                .thenReturn(Optional.of(new AiModelSetting("modelo/b")));
        assertEquals("modelo/b", service.currentModel());
    }

    @Test
    void persisteModeloPermitido() {
        when(repository.findById(AiModelSetting.SINGLETON_ID)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertEquals("modelo/b", service.updateModel("modelo/b"));
        verify(repository).save(any(AiModelSetting.class));
    }

    @Test
    void rejeitaModeloForaDaLista() {
        assertThrows(IllegalArgumentException.class, () -> service.updateModel("modelo/x"));
        verify(repository, never()).save(any());
    }

    // O banco pode ter um modelo pago salvo de antes de a instalação virar
    // "só gratuitos". Sem esta guarda a demo voltava a faturar sozinha.
    @Test
    void modeloPagoSalvoNoBancoNaoEhUsadoQuandoSoGratuitos() {
        when(openRouter.freeModelsOnly()).thenReturn(true);
        when(repository.findById(AiModelSetting.SINGLETON_ID))
                .thenReturn(Optional.of(new AiModelSetting("anthropic/claude-sonnet-4.5")));

        assertEquals("modelo/a", service.currentModel());
    }

    /** Com a flag desligada o modelo pago salvo continua valendo. */
    @Test
    void comFlagDesligadaModeloPagoSalvoEhRespeitado() {
        when(openRouter.freeModelsOnly()).thenReturn(false);
        when(repository.findById(AiModelSetting.SINGLETON_ID))
                .thenReturn(Optional.of(new AiModelSetting("anthropic/claude-sonnet-4.5")));

        assertEquals("anthropic/claude-sonnet-4.5", service.currentModel());
    }
}
