package com.hrb.mlmanager.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.UpdateTimestamp;

/** Configuração global do modelo usado pelo assistente. Existe uma única linha (id=1). */
@Entity
@Table(name = "ai_model_settings")
public class AiModelSetting {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(name = "model_id", nullable = false, length = 200)
    private String modelId;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    protected AiModelSetting() {}

    public AiModelSetting(String modelId) {
        this.id = SINGLETON_ID;
        this.modelId = modelId;
    }

    public Long getId() {
        return id;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }
}
