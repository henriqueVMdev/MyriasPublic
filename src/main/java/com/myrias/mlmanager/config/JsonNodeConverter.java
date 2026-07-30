package com.myrias.mlmanager.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Serializa um {@link JsonNode} arbitrário como JSON numa coluna texto.
 * Equivale ao {@code Column(JSON)} do SQLAlchemy (payload/response do
 * OperationLog), portável entre Postgres e H2 sem jsonb nativo.
 * ponytail: texto + Jackson, mesmo critério do {@link StringListJsonConverter}.
 */
@Converter
public class JsonNodeConverter implements AttributeConverter<JsonNode, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(JsonNode attribute) {
        if (attribute == null || attribute.isNull()) return null;
        return attribute.toString();
    }

    @Override
    public JsonNode convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return null;
        try {
            return MAPPER.readTree(dbData);
        } catch (Exception e) {
            return null;
        }
    }
}
