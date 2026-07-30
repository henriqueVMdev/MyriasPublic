package com.myrias.mlmanager.meli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myrias.mlmanager.meli.MeliClient.MeliResponse;
import com.myrias.mlmanager.meli.MeliItemsService.UpdateResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Rotas de anúncios. Espelho de backend/app/api/items.py.
 * A sessão do painel já é exigida pelo {@link com.myrias.mlmanager.auth.AppAuthFilter};
 * a conta ML ativa vem de {@link MeliAuthService#requireActiveAccountId()}.
 */
@RestController
@RequestMapping("/api/items")
public class ItemsController {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> ALLOWED_IMAGE_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp", "image/gif");
    private static final long MAX_UPLOAD_SIZE = 10L * 1024 * 1024;
    private static final Set<String> VALID_STATUS = Set.of("active", "paused", "closed");
    private static final Set<String> SUPPORTED_ATTR_TYPES =
            Set.of("string", "number", "boolean", "number_unit", "list");

    private final MeliItemsService service;
    private final MeliClient client;
    private final MeliAuthService auth;
    private final com.myrias.mlmanager.quality.QualityJobs quality;

    public ItemsController(MeliItemsService service, MeliClient client, MeliAuthService auth,
                           com.myrias.mlmanager.quality.QualityJobs quality) {
        this.service = service;
        this.client = client;
        this.auth = auth;
        this.quality = quality;
    }

    public record StatusUpdate(String status) {}
    public record DescriptionUpdate(String plain_text) {}
    public record PicturesUpdate(JsonNode pictures) {}

    @GetMapping("")
    public Map<String, Object> listItems(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String seller_sku,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        long userId = auth.requireActiveAccountId();
        int safeLimit = Math.max(1, Math.min(limit, 50));
        int safeOffset = Math.max(0, offset);
        return service.searchItems(userId, status, seller_sku, q, safeOffset, safeLimit);
    }

    @PostMapping("/upload-picture")
    public MeliResponse uploadPicture(@RequestParam("file") MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType != null && !ALLOWED_IMAGE_TYPES.contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Tipo de arquivo não permitido. Use JPG, PNG ou WebP.");
        }
        if (file.getSize() > MAX_UPLOAD_SIZE) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Arquivo muito grande. Máximo 10MB.");
        }
        String raw = file.getOriginalFilename();
        String safeName = (raw == null || raw.isEmpty() ? "image.jpg" : raw).replaceAll("[^\\w.\\-]", "_");
        try {
            return client.uploadPicture(file.getBytes(), safeName);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falha ao ler o arquivo enviado.");
        }
    }

    @GetMapping("/category-attributes/{categoryId}")
    public List<Map<String, Object>> categoryAttributes(@PathVariable String categoryId) {
        MeliResponse resp = client.getPublic("/categories/" + categoryId + "/attributes");
        if (resp.status() != 200 || resp.data() == null || !resp.data().isArray()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(resp.status() == 200 ? 502 : resp.status()),
                    "Erro ao buscar atributos da categoria");
        }

        List<Map<String, Object>> editable = new ArrayList<>();
        for (JsonNode attr : resp.data()) {
            JsonNode tags = attr.path("tags");
            String valueType = attr.path("value_type").asText("string");

            if (tags.path("read_only").asBoolean()
                    || tags.path("hidden").asBoolean()
                    || tags.path("inferred").asBoolean()
                    || tags.path("catalog_only").asBoolean()
                    || !SUPPORTED_ATTR_TYPES.contains(valueType)) {
                continue;
            }

            Map<String, Object> e = new HashMap<>();
            e.put("id", attr.path("id").asText());
            e.put("name", attr.path("name").asText(attr.path("id").asText()));
            e.put("value_type", valueType);
            e.put("values", idNamePairs(attr.path("values"), 50));
            Map<String, Object> tagOut = new HashMap<>();
            tagOut.put("required", tags.path("required").asBoolean());
            tagOut.put("catalog_required", tags.path("catalog_required").asBoolean());
            tagOut.put("variation_attribute", tags.path("variation_attribute").asBoolean());
            tagOut.put("allow_custom_value", !tags.path("fixed").asBoolean());
            e.put("tags", tagOut);
            e.put("tooltip", attr.path("tooltip").asText(""));
            e.put("default_unit", attr.path("default_unit").isMissingNode() ? null : attr.path("default_unit").asText());
            e.put("allowed_units", idNamePairs(attr.path("allowed_units"), Integer.MAX_VALUE));
            editable.add(e);
        }
        return editable;
    }

    @GetMapping("/{itemId}")
    public JsonNode getItem(@PathVariable String itemId) {
        return service.getItem(itemId);
    }

    @PutMapping("/{itemId}")
    public Map<String, Object> updateItem(@PathVariable String itemId, @RequestBody ItemUpdate body) {
        ObjectNode updates = MAPPER.valueToTree(body);
        UpdateResult r = service.updateItem(itemId, updates);
        if (r.status() != 200) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(r.status()), errorDetail(r.data()));
        }
        quality.enqueueItemRevalidation(auth.requireActiveAccountId(), List.of(itemId), "item_update");
        Map<String, Object> out = new HashMap<>();
        out.put("ok", true);
        out.put("ignored_attrs", r.ignoredAttrs());
        return out;
    }

    @PutMapping("/{itemId}/status")
    public Map<String, Object> updateStatus(@PathVariable String itemId, @RequestBody StatusUpdate body) {
        if (body.status() == null || !VALID_STATUS.contains(body.status())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "status deve ser active, paused ou closed");
        }
        UpdateResult r = service.updateStatus(itemId, body.status());
        if (r.status() == 200) {
            quality.enqueueItemRevalidation(auth.requireActiveAccountId(), List.of(itemId), "status_update");
        }
        return resultMap(r);
    }

    @PutMapping("/{itemId}/description")
    public MeliResponse updateDescription(@PathVariable String itemId, @RequestBody DescriptionUpdate body) {
        String text = body.plain_text();
        if (text == null || text.length() > 50000) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "plain_text é obrigatório e limitado a 50000 caracteres");
        }
        MeliResponse resp = service.updateDescription(itemId, text);
        if (resp.status() == 200 || resp.status() == 201) {
            quality.enqueueItemRevalidation(auth.requireActiveAccountId(), List.of(itemId), "description_update");
        }
        return resp;
    }

    @PutMapping("/{itemId}/pictures")
    public Map<String, Object> updatePictures(@PathVariable String itemId, @RequestBody PicturesUpdate body) {
        if (body.pictures() == null || !body.pictures().isArray() || body.pictures().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "pictures não pode ser vazio");
        }
        UpdateResult r = service.updatePictures(itemId, body.pictures());
        if (r.status() == 200) {
            quality.enqueueItemRevalidation(auth.requireActiveAccountId(), List.of(itemId), "pictures_update");
        }
        return resultMap(r);
    }

    // ---- Internos ------------------------------------------------------------

    private static Map<String, Object> resultMap(UpdateResult r) {
        Map<String, Object> out = new HashMap<>();
        out.put("status", r.status());
        out.put("data", r.data());
        out.put("ignored_attrs", r.ignoredAttrs());
        return out;
    }

    private static List<Map<String, Object>> idNamePairs(JsonNode arr, int max) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode v : arr) {
            if (out.size() >= max) break;
            if (v.path("id").isMissingNode()) continue;
            Map<String, Object> m = new HashMap<>();
            m.put("id", v.path("id").asText());
            m.put("name", v.path("name").asText(null));
            out.add(m);
        }
        return out;
    }

    private static String errorDetail(JsonNode mlData) {
        if (mlData == null) return "Erro ao atualizar item";
        if (mlData.hasNonNull("message")) return mlData.get("message").asText();
        if (mlData.hasNonNull("error")) return mlData.get("error").asText();
        return mlData.toString();
    }
}
