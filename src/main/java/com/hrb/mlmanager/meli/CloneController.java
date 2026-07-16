package com.hrb.mlmanager.meli;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hrb.mlmanager.auth.PanelSecurity;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/clone")
public class CloneController {

    private final MeliCloneService service;
    private final MeliAuthService auth;
    private final PanelSecurity security;

    public CloneController(MeliCloneService service, MeliAuthService auth, PanelSecurity security) {
        this.service = service;
        this.auth = auth;
        this.security = security;
    }

    public record PreviewRequest(@JsonProperty("item_id") String itemId) {}

    @PostMapping("/preview")
    public ResponseEntity<?> preview(@RequestBody PreviewRequest body) {
        if (body == null || body.itemId() == null || body.itemId().isBlank()) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "item_id e obrigatorio");
        }
        try {
            return ResponseEntity.ok(service.preview(body.itemId()));
        } catch (ResponseStatusException e) {
            String message = e.getReason();
            if (message == null || message.isBlank()) message = e.getMessage();
            return error(HttpStatus.valueOf(e.getStatusCode().value()), message);
        } catch (Exception e) {
            String message = e.getMessage();
            if (message == null || message.isBlank()) {
                message = "Nao foi possivel obter o anuncio. Verifique o link/ID e tente novamente.";
            }
            return error(HttpStatus.BAD_REQUEST, message);
        }
    }

    @PostMapping("/create")
    public ResponseEntity<?> create(@RequestBody ObjectNode body, HttpServletRequest request) {
        security.require(request, "clone_listing");
        validateCreatePayload(body);
        ResponseEntity<?> missing = missingAttrsResponse(body);
        if (missing != null) return missing;
        try {
            return ResponseEntity.ok(service.create(body, auth.requireActiveAccountId(), null));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/create-multi")
    public ResponseEntity<?> createMulti(@RequestBody ObjectNode body, HttpServletRequest request) {
        security.require(request, "clone_listing");
        List<Long> userIds = userIds(body.path("user_ids"));
        String batchId = body.path("batch_id").asText(null);
        ObjectNode cloneData = body.deepCopy();
        cloneData.remove("user_ids");
        cloneData.remove("batch_id");
        validateCreatePayload(cloneData);
        ResponseEntity<?> missing = missingAttrsResponse(cloneData);
        if (missing != null) return missing;
        try {
            return ResponseEntity.ok(service.createMulti(cloneData, userIds, batchId));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private ResponseEntity<?> missingAttrsResponse(ObjectNode body) {
        String categoryId = body.path("category_id").asText("");
        if (categoryId.isBlank()) return null;
        List<Map<String, Object>> missing = service.checkMissingRequiredAttrs(body, categoryId);
        if (missing.isEmpty()) return null;
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("detail", Map.of("needs_input", true, "missing_attrs", missing)));
    }

    private static void validateCreatePayload(ObjectNode body) {
        if (body.path("title").asText("").isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "title e obrigatorio");
        }
        if (body.path("category_id").asText("").isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "category_id e obrigatorio");
        }
        if (!body.path("pictures").isArray() || body.path("pictures").isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "pictures nao pode ser vazio");
        }
        if (body.path("price").asDouble(0) <= 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "price deve ser maior que zero");
        }
        if (body.path("available_quantity").asInt(0) < 1) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "available_quantity deve ser maior ou igual a 1");
        }
    }

    private static List<Long> userIds(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "user_ids nao pode ser vazio");
        }
        List<Long> out = new ArrayList<>();
        for (JsonNode id : node) {
            if (id.canConvertToLong()) out.add(id.asLong());
        }
        if (out.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "user_ids nao pode ser vazio");
        }
        return out;
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        String detail = message == null || message.isBlank()
                ? "Erro ao processar a solicitacao."
                : message;
        return ResponseEntity.status(status).body(Map.of("detail", detail));
    }
}
