package com.myrias.mlmanager.ai;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lê e persiste o modelo global escolhido pelo administrador. */
@Service
public class AiModelSettingsService {

    private static final Logger log = LoggerFactory.getLogger(AiModelSettingsService.class);

    private final AiModelSettingRepository repository;
    private final OpenRouterClient openRouter;

    public AiModelSettingsService(AiModelSettingRepository repository, OpenRouterClient openRouter) {
        this.repository = repository;
        this.openRouter = openRouter;
    }

    public List<OpenRouterClient.ModelOption> availableModels() {
        return openRouter.availableModels();
    }

    public List<OpenRouterClient.ModelInfo> allModels() {
        return openRouter.allModels();
    }

    public List<OpenRouterClient.ModelInfo> refreshModels() {
        return openRouter.refreshModels();
    }

    @Transactional(readOnly = true)
    public String currentModel() {
        String configured = repository.findById(AiModelSetting.SINGLETON_ID)
                .map(AiModelSetting::getModelId)
                .orElse(null);
        if (configured == null || configured.isBlank()) {
            return openRouter.defaultModel();
        }
        // O modelo salvo pode ter sido escolhido antes de o app virar "só gratuitos"
        // (ou com a flag desligada). Sem esta guarda a demo voltaria a faturar num
        // modelo pago sem ninguém tocar em nada.
        if (openRouter.freeModelsOnly() && !isSelectable(configured)) {
            log.warn("Modelo salvo '{}' não é gratuito; usando '{}'.",
                    configured, openRouter.defaultModel());
            return openRouter.defaultModel();
        }
        return configured;
    }

    private boolean isSelectable(String model) {
        return openRouter.availableModels().stream()
                .anyMatch(option -> option.id().equals(model));
    }

    @Transactional
    public String updateModel(String model) {
        String requested = model == null ? "" : model.strip();
        if (requested.isBlank() || !isSelectable(requested)) {
            throw new IllegalArgumentException(openRouter.freeModelsOnly()
                    ? "Modelo inválido: só modelos gratuitos com suporte a tools "
                      + "podem ser selecionados nesta instalação."
                    : "Modelo OpenRouter invalido ou nao permitido.");
        }

        AiModelSetting setting = repository.findById(AiModelSetting.SINGLETON_ID)
                .orElseGet(() -> new AiModelSetting(requested));
        setting.setModelId(requested);
        repository.save(setting);
        return requested;
    }
}
