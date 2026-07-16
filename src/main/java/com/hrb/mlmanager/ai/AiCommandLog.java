package com.hrb.mlmanager.ai;

import com.hrb.mlmanager.config.StringListJsonConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Auditoria de uma mensagem enviada por um colaborador ao agente. */
@Entity
@Table(name = "ai_command_logs")
public class AiCommandLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_user_id")
    private Long appUserId;

    @Column(nullable = false, length = 60)
    private String username;

    @Column(name = "display_name", length = 120)
    private String displayName;

    @Column(nullable = false, columnDefinition = "text")
    private String command;

    @Column(columnDefinition = "text")
    private String reply;

    @Column(name = "selected_model", nullable = false, length = 200)
    private String selectedModel;

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "actual_models", nullable = false, columnDefinition = "text")
    private List<String> actualModels = List.of();

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "tool_events", nullable = false, columnDefinition = "text")
    private List<String> toolEvents = List.of();

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "generation_ids", nullable = false, columnDefinition = "text")
    private List<String> generationIds = List.of();

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "prompt_tokens", nullable = false)
    private long promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private long completionTokens;

    @Column(name = "total_tokens", nullable = false)
    private long totalTokens;

    @Column(nullable = false, precision = 20, scale = 10)
    private BigDecimal cost = BigDecimal.ZERO;

    @Column(name = "request_count", nullable = false)
    private int requestCount;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AiCommandLog() {}

    public AiCommandLog(Long appUserId, String username, String displayName,
                        String command, String selectedModel) {
        this.appUserId = appUserId;
        this.username = username;
        this.displayName = displayName;
        this.command = command;
        this.selectedModel = selectedModel;
        this.status = "running";
    }

    @PrePersist
    void onInsert() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getAppUserId() { return appUserId; }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public String getCommand() { return command; }
    public String getReply() { return reply; }
    public String getSelectedModel() { return selectedModel; }
    public List<String> getActualModels() { return actualModels; }
    public List<String> getToolEvents() { return toolEvents; }
    public List<String> getGenerationIds() { return generationIds; }
    public String getStatus() { return status; }
    public String getErrorMessage() { return errorMessage; }
    public long getPromptTokens() { return promptTokens; }
    public long getCompletionTokens() { return completionTokens; }
    public long getTotalTokens() { return totalTokens; }
    public BigDecimal getCost() { return cost; }
    public int getRequestCount() { return requestCount; }
    public long getDurationMs() { return durationMs; }
    public Instant getCreatedAt() { return createdAt; }

    public void complete(String reply, List<String> actualModels, List<String> toolEvents,
                         List<String> generationIds, String status, String errorMessage,
                         long promptTokens, long completionTokens, long totalTokens,
                         BigDecimal cost, int requestCount, long durationMs) {
        this.reply = reply;
        this.actualModels = actualModels == null ? List.of() : List.copyOf(actualModels);
        this.toolEvents = toolEvents == null ? List.of() : List.copyOf(toolEvents);
        this.generationIds = generationIds == null ? List.of() : List.copyOf(generationIds);
        this.status = status;
        this.errorMessage = errorMessage;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.cost = cost == null ? BigDecimal.ZERO : cost;
        this.requestCount = requestCount;
        this.durationMs = durationMs;
    }
}
