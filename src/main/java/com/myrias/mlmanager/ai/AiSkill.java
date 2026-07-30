package com.myrias.mlmanager.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** Instrução especializada configurável pelo administrador. */
@Entity
@Table(name = "ai_skills")
public class AiSkill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(nullable = false, columnDefinition = "text")
    private String instructions;

    @Column(nullable = false)
    private boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    protected AiSkill() {}

    public AiSkill(String name, String description, String instructions, boolean enabled) {
        this.name = name;
        this.description = description;
        this.instructions = instructions;
        this.enabled = enabled;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getInstructions() { return instructions; }
    public boolean isEnabled() { return enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public void setInstructions(String instructions) { this.instructions = instructions; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
