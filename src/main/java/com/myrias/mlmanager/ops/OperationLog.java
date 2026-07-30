package com.myrias.mlmanager.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.myrias.mlmanager.config.JsonNodeConverter;
import com.myrias.mlmanager.config.StringListJsonConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;

/**
 * Registro de cada operação sobre anúncios (update, status, descrição, ...).
 * Espelho de backend/app/models/operation_log.py.
 *
 * {@code itemIds}/{@code payload}/{@code response} viram JSON em coluna texto
 * (conversores) — equivalente ao ARRAY/JSON do Postgres, portável para H2.
 * {@code actor} é preenchido a partir do {@link CurrentActor} no momento do
 * insert, como o default via ContextVar do Python.
 */
@Entity
@Table(name = "operation_logs")
public class OperationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "operation_type", nullable = false, length = 50)
    private String operationType;

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "item_ids", columnDefinition = "text")
    private List<String> itemIds;

    @Convert(converter = JsonNodeConverter.class)
    @Column(columnDefinition = "text")
    private JsonNode payload;

    @Convert(converter = JsonNodeConverter.class)
    @Column(columnDefinition = "text")
    private JsonNode response;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    /** user_id do vendedor ML (estoura int32 — por isso Long). */
    @Column(name = "user_id")
    private Long userId;

    /** Agrupa várias linhas numa única operação do usuário (clone em massa). */
    @Column(name = "batch_id", length = 64)
    private String batchId;

    @Column(length = 120)
    private String actor;

    protected OperationLog() {}

    public OperationLog(String operationType, List<String> itemIds, JsonNode payload,
                        JsonNode response, String status, String errorMessage) {
        this.operationType = operationType;
        this.itemIds = itemIds;
        this.payload = payload;
        this.response = response;
        this.status = status;
        this.errorMessage = errorMessage;
    }

    /** Carimba created_at em UTC e o ator da requisição, como o Python no insert. */
    @PrePersist
    void onInsert() {
        if (createdAt == null) createdAt = Instant.now();
        if (actor == null) actor = CurrentActor.get();
    }

    public Long getId() { return id; }
    public String getOperationType() { return operationType; }
    public List<String> getItemIds() { return itemIds; }
    public JsonNode getPayload() { return payload; }
    public JsonNode getResponse() { return response; }
    public String getStatus() { return status; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Long getUserId() { return userId; }
    public String getBatchId() { return batchId; }
    public String getActor() { return actor; }

    public void setUserId(Long userId) { this.userId = userId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
}
