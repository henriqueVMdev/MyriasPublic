package com.hrb.mlmanager.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;

/**
 * Serializa List&lt;String&gt; como JSON numa coluna texto. Equivalente ao
 * Column(JSON) do SQLAlchemy, mas portável entre Postgres e H2 sem depender
 * de tipo jsonb nativo. ponytail: texto + Jackson em vez de @JdbcTypeCode,
 * porque é DB-agnóstico e zero mágica.
 */
@Converter
public class StringListJsonConverter implements AttributeConverter<List<String>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        try {
            return MAPPER.writeValueAsString(attribute == null ? List.of() : attribute);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao serializar permissions", e);
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return List.of();
        try {
            return MAPPER.readValue(dbData, TYPE);
        } catch (Exception e) {
            return List.of();
        }
    }
}
