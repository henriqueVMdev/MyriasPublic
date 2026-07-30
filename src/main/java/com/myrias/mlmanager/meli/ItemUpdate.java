package com.myrias.mlmanager.meli;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Campos editáveis de um item. Espelho de schemas/items.py::ItemUpdate.
 * {@code @JsonInclude(NON_NULL)} reproduz o {@code model_dump(exclude_none=True)}
 * do pydantic: só os campos preenchidos vão no JSON enviado ao ML. Os campos
 * estruturados (pictures/attributes/...) são {@link JsonNode} — passam adiante
 * sem perda, como os {@code list[dict]} do Python.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ItemUpdate {

    public String title;
    public Double price;

    @JsonProperty("available_quantity")
    public Integer availableQuantity;

    public String status;
    public JsonNode pictures;
    public JsonNode attributes;

    @JsonProperty("sale_terms")
    public JsonNode saleTerms;

    public JsonNode shipping;

    @JsonProperty("seller_custom_field")
    public String sellerCustomField;

    @JsonProperty("video_id")
    public String videoId;
}
