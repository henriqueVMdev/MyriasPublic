package com.myrias.mlmanager.perf;

import com.fasterxml.jackson.databind.JsonNode;
import com.myrias.mlmanager.config.JsonNodeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * Snapshot de performance de uma conta ML. Substitui os arquivos JSON em disco
 * do Python (scripts/.cache/perf_*.json) por linhas no banco — uma por conta e
 * tipo ({@code kind} = inventory | sales | ads | visits).
 *
 * O corpo inteiro do snapshot (o mesmo objeto que o Python serializava: items,
 * by_item, totals, ...) fica em {@code data} como JSON numa coluna texto
 * (portável H2/Postgres via {@link JsonNodeConverter}). {@code scannedAt} é
 * coluna própria para checar idade sem desserializar o corpo.
 */
@Entity
@Table(name = "perf_snapshots",
        uniqueConstraints = @UniqueConstraint(name = "uq_perf_user_kind", columnNames = {"user_id", "kind"}))
public class PerfSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 20)
    private String kind;

    @Column(name = "scanned_at", nullable = false)
    private Instant scannedAt;

    @Convert(converter = JsonNodeConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private JsonNode data;

    protected PerfSnapshot() {}

    public PerfSnapshot(Long userId, String kind, Instant scannedAt, JsonNode data) {
        this.userId = userId;
        this.kind = kind;
        this.scannedAt = scannedAt;
        this.data = data;
    }

    public Long getUserId() { return userId; }
    public String getKind() { return kind; }
    public Instant getScannedAt() { return scannedAt; }
    public JsonNode getData() { return data; }

    public void setScannedAt(Instant scannedAt) { this.scannedAt = scannedAt; }
    public void setData(JsonNode data) { this.data = data; }
}
