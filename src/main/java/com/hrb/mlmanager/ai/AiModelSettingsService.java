package com.hrb.mlmanager.ai;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lê e persiste o modelo global escolhido pelo administrador. */
@Service
public class AiModelSettingsService {

    private final AiModelSettingRepository repository;
    private final OpenRouterClient openRouter;

    public AiModelSettingsService(AiModelSettingRepository repository, OpenRouterClient openRouter) {
        this.repository = repository;
        this.openRouter = openRouter;
    }

    public List<OpenRouterClient.ModelOption> availableModels() {
        return openRouter.availableModels();
    }

    @Transactional(readOnly = true)
    public String currentModel() {
        String configured = repository.findById(AiModelSetting.SINGLETON_ID)
                .map(AiModelSetting::getModelId)
                .orElse(null);
        return configured == null || configured.isBlank()
                ? openRouter.defaultModel()
                : configured;
    }

    @Transactional
    public String updateModel(String model) {
        String requested = model == null ? "" : model.strip();
        boolean available = openRouter.availableModels().stream()
                .anyMatch(option -> option.id().equals(requested));
        if (requested.isBlank() || !available) {
            throw new IllegalArgumentException("Modelo OpenRouter invalido ou nao permitido.");
        }

        AiModelSetting setting = repository.findById(AiModelSetting.SINGLETON_ID)
                .orElseGet(() -> new AiModelSetting(requested));
        setting.setModelId(requested);
        repository.save(setting);
        return requested;
    }
}
