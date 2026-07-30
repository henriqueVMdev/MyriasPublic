package com.hrb.mlmanager.meli;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hrb.mlmanager.auth.PanelSecurity;
import com.hrb.mlmanager.quality.QualityJobs;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Rotas /api/bulk, espelhando backend/app/api/bulk.py. */
@RestController
@RequestMapping("/api/bulk")
public class BulkController {

    private final MeliBulkService service;
    private final MeliAuthService auth;
    private final PanelSecurity security;
    private final QualityJobs quality;

    public BulkController(MeliBulkService service, MeliAuthService auth,
                          PanelSecurity security, QualityJobs quality) {
        this.service = service;
        this.auth = auth;
        this.security = security;
        this.quality = quality;
    }

    /** Revalida a qualidade dos itens que passaram (espelho de _enqueue_successful_groups). */
    private void enqueueSuccessfulGroups(List<Map<String, Object>> groups, Map<String, Object> result, String source) {
        Set<String> failed = new HashSet<>();
        if (result.get("errors") instanceof List<?> errors) {
            for (Object e : errors) {
                if (e instanceof Map<?, ?> m && m.get("item_id") != null) failed.add(String.valueOf(m.get("item_id")));
            }
        }
        for (Map<String, Object> group : groups) {
            if (!(group.get("item_ids") instanceof List<?> ids)) continue;
            List<String> successful = new ArrayList<>();
            for (Object id : ids) {
                String s = String.valueOf(id);
                if (!failed.contains(s)) successful.add(s);
            }
            if (!successful.isEmpty()) {
                quality.enqueueItemRevalidation(((Number) group.get("user_id")).longValue(), successful, source);
            }
        }
    }

    public record BulkUpdate(
            @JsonProperty("item_ids") List<String> itemIds,
            JsonNode updates) {}

    public record BulkUpdateBySku(String sku, JsonNode updates) {}

    public record AccountItems(
            @JsonProperty("user_id") Long userId,
            @JsonProperty("item_ids") List<String> itemIds) {}

    public record BulkUpdateMulti(
            List<AccountItems> groups,
            JsonNode updates,
            String sku,
            Map<String, String> titles,
            JsonNode before,
            @JsonProperty("batch_id") String batchId) {}

    public record BulkDescriptionUpdateMulti(
            List<AccountItems> groups,
            String description,
            String sku,
            @JsonProperty("batch_id") String batchId) {}

    public record BulkPositionsUpdate(
            List<AccountItems> groups,
            List<Map<String, Object>> positions,
            String sku,
            @JsonProperty("batch_id") String batchId) {}

    public record BulkCompatibilitiesUpdate(
            List<AccountItems> groups,
            @JsonProperty("product_ids") List<String> productIds,
            String mode,
            String sku,
            @JsonProperty("vehicle_names") List<String> vehicleNames,
            List<String> notes,
            List<Map<String, Object>> positions,
            @JsonProperty("batch_id") String batchId) {}

    @GetMapping("/skus")
    public List<Map<String, Object>> listSkus() {
        return service.getSkus(auth.requireActiveAccountId());
    }

    @GetMapping("/skus/all")
    public List<Map<String, Object>> listSkusAllAccounts() {
        return service.getSkusAllAccounts();
    }

    @GetMapping("/sku/{sku}")
    public List<JsonNode> getItemsBySku(@PathVariable String sku) {
        return service.getItemsBySku(auth.requireActiveAccountId(), sku);
    }

    @GetMapping("/sku/{sku}/all")
    public Map<String, Object> getItemsBySkuAllAccounts(@PathVariable String sku) {
        return Map.of("groups", service.getItemsBySkuAllAccounts(sku));
    }

    @PostMapping("/update")
    public Map<String, Object> bulkUpdate(@RequestBody BulkUpdate body, HttpServletRequest request) {
        security.require(request, "bulk_edit");
        if (body.itemIds() == null || body.itemIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "item_ids nao pode ser vazio");
        }
        long userId = auth.requireActiveAccountId();
        Map<String, Object> result = service.bulkUpdate(body.itemIds(), objectUpdates(body.updates()), userId);
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("user_id", userId);
        group.put("item_ids", body.itemIds());
        enqueueSuccessfulGroups(List.of(group), result, "bulk_update");
        return result;
    }

    @PostMapping("/update-by-sku")
    public Map<String, Object> bulkUpdateBySku(@RequestBody BulkUpdateBySku body, HttpServletRequest request) {
        security.require(request, "bulk_edit");
        if (body.sku() == null || body.sku().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "sku e obrigatorio");
        }
        return service.bulkUpdateBySku(auth.requireActiveAccountId(), body.sku(), objectUpdates(body.updates()));
    }

    @PostMapping("/update-multi")
    public Map<String, Object> bulkUpdateMulti(@RequestBody BulkUpdateMulti body, HttpServletRequest request) {
        security.require(request, "bulk_edit");
        if (body.groups() == null || body.groups().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "groups nao pode ser vazio");
        }
        List<Map<String, Object>> gs = groups(body.groups());
        Map<String, Object> result = service.bulkUpdateMultiAccount(gs, objectUpdates(body.updates()),
                body.sku(), body.titles(), body.before(), body.batchId());
        enqueueSuccessfulGroups(gs, result, "bulk_update_multi");
        return result;
    }

    @GetMapping("/description/item/{itemId}")
    public Map<String, Object> getItemDescription(@PathVariable String itemId) {
        return Map.of("item_id", itemId, "plain_text",
                service.getItemDescription(itemId, auth.requireActiveAccountId()));
    }

    @PostMapping("/description/update-multi")
    public Map<String, Object> bulkUpdateDescriptionMulti(@RequestBody BulkDescriptionUpdateMulti body,
                                                          HttpServletRequest request) {
        security.require(request, "bulk_edit");
        if (body.description() == null || body.description().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "description e obrigatorio");
        }
        List<Map<String, Object>> gs = groups(body.groups());
        Map<String, Object> result = service.bulkUpdateDescriptionMulti(gs, body.description(), body.sku(), body.batchId());
        enqueueSuccessfulGroups(gs, result, "description_update_multi");
        return result;
    }

    @GetMapping("/debug/item-package/{itemId}")
    public Map<String, Object> debugItemPackage(@PathVariable String itemId) {
        return service.debugItemPackage(itemId, auth.requireActiveAccountId());
    }

    @GetMapping("/compatibilities/position-attributes/{categoryId}")
    public List<Map<String, Object>> getPositionAttributes(@PathVariable String categoryId) {
        return service.getPositionAttributes(categoryId, auth.requireActiveAccountId());
    }

    @GetMapping("/compatibilities/from-ref")
    public Map<String, Object> getCompatibilitiesFromRef(@RequestParam String ref) {
        if (ref == null || ref.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "ref e obrigatorio");
        }
        return service.getCompatibilitiesFromRef(ref);
    }

    @GetMapping("/compatibilities/item/{itemId}")
    public Map<String, Object> getItemCompatibilities(@PathVariable String itemId) {
        return service.getItemCompatibilitiesWithPositions(itemId, auth.requireActiveAccountId());
    }

    @PostMapping("/compatibilities/update")
    public Map<String, Object> bulkUpdateCompatibilities(@RequestBody BulkCompatibilitiesUpdate body,
                                                         HttpServletRequest request) {
        security.require(request, "bulk_edit");
        if (body.groups() == null || body.groups().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "groups nao pode ser vazio");
        }
        if (body.productIds() == null || body.productIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "product_ids nao pode ser vazio");
        }
        List<Map<String, Object>> gs = groups(body.groups());
        Map<String, Object> result = service.bulkUpdateCompatibilities(gs, body.productIds(), body.mode(),
                body.sku(), body.vehicleNames(), body.notes(), body.positions(), body.batchId());
        enqueueSuccessfulGroups(gs, result, "compatibilities_update");
        return result;
    }

    @PostMapping("/positions/update")
    public Map<String, Object> bulkUpdatePositions(@RequestBody BulkPositionsUpdate body,
                                                   HttpServletRequest request) {
        security.require(request, "bulk_edit");
        if (body.groups() == null || body.groups().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "groups nao pode ser vazio");
        }
        if (body.positions() == null || body.positions().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "positions nao pode ser vazio");
        }
        List<Map<String, Object>> gs = groups(body.groups());
        Map<String, Object> result = service.bulkUpdatePositions(gs, body.positions(), body.sku(), body.batchId());
        enqueueSuccessfulGroups(gs, result, "positions_update");
        return result;
    }

    private static ObjectNode objectUpdates(JsonNode updates) {
        if (updates == null || !updates.isObject() || updates.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "updates nao pode ser vazio");
        }
        return (ObjectNode) updates;
    }

    private static List<Map<String, Object>> groups(List<AccountItems> groups) {
        if (groups == null) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (AccountItems g : groups) {
            if (g.userId() == null || g.itemIds() == null || g.itemIds().isEmpty()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("user_id", g.userId());
            m.put("item_ids", g.itemIds());
            out.add(m);
        }
        return out;
    }
}
