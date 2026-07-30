package com.myrias.mlmanager.meli;

import com.fasterxml.jackson.databind.JsonNode;
import com.myrias.mlmanager.auth.PanelSecurity;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/promotions")
public class PromotionsController {

    private final MeliPromotionsService service;
    private final MeliAuthService auth;
    private final PanelSecurity security;

    public PromotionsController(MeliPromotionsService service, MeliAuthService auth, PanelSecurity security) {
        this.service = service;
        this.auth = auth;
        this.security = security;
    }

    @GetMapping("")
    public Map<String, Object> listPromotions(HttpServletRequest request) {
        security.require(request, "promocoes");
        return Map.of("promotions", service.listPromotions(auth.requireActiveAccountId()));
    }

    @GetMapping("/coupons")
    public Map<String, Object> listCoupons(HttpServletRequest request) {
        security.require(request, "promocoes");
        return Map.of("coupons", service.listCoupons(auth.requireActiveAccountId()));
    }

    @PostMapping("/coupons")
    public Map<String, Object> createCoupon(@RequestBody JsonNode body, HttpServletRequest request) {
        security.require(request, "manage_promotions");
        return service.createCoupon(auth.requireActiveAccountId(), body);
    }

    @PutMapping("/coupons/{couponId}")
    public Map<String, Object> updateCoupon(@PathVariable String couponId,
                                            @RequestBody JsonNode body,
                                            HttpServletRequest request) {
        security.require(request, "manage_promotions");
        return service.updateCoupon(auth.requireActiveAccountId(), couponId, body);
    }

    @DeleteMapping("/coupons/{couponId}")
    public Map<String, Object> endCoupon(@PathVariable String couponId, HttpServletRequest request) {
        security.require(request, "manage_promotions");
        return service.endCoupon(auth.requireActiveAccountId(), couponId);
    }

    @GetMapping("/{promotionId}/items")
    public Map<String, Object> listItems(@PathVariable String promotionId,
                                         @RequestParam String promotion_type,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String search_after,
                                         HttpServletRequest request) {
        security.require(request, "promocoes");
        return service.listPromotionItems(auth.requireActiveAccountId(),
                promotionId, promotion_type, status, search_after);
    }

    @GetMapping("/{promotionId}/search")
    public Map<String, Object> search(@PathVariable String promotionId,
                                      @RequestParam String q,
                                      @RequestParam String promotion_type,
                                      HttpServletRequest request) {
        security.require(request, "promocoes");
        if (q == null || q.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "q e obrigatorio");
        }
        return service.searchInPromotion(auth.requireActiveAccountId(), promotionId, promotion_type, q);
    }

    @PostMapping("/items")
    public Map<String, Object> addItems(@RequestBody JsonNode body, HttpServletRequest request) {
        security.require(request, "manage_promotions");
        String promotionId = body.path("promotion_id").asText("");
        String promotionType = body.path("promotion_type").asText("");
        List<JsonNode> items = array(body.path("items"));
        if (promotionId.isBlank() || promotionType.isBlank() || items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "promotion_id, promotion_type e items sao obrigatorios");
        }
        return service.addItems(auth.requireActiveAccountId(), promotionId, promotionType, items);
    }

    @DeleteMapping("/items")
    public Map<String, Object> removeItems(@RequestBody JsonNode body, HttpServletRequest request) {
        security.require(request, "manage_promotions");
        String promotionId = body.path("promotion_id").asText("");
        String promotionType = body.path("promotion_type").asText("");
        List<String> itemIds = new ArrayList<>();
        for (JsonNode n : body.path("item_ids")) itemIds.add(n.asText());
        if (promotionId.isBlank() || promotionType.isBlank() || itemIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "promotion_id, promotion_type e item_ids sao obrigatorios");
        }
        return service.removeItems(auth.requireActiveAccountId(), promotionId, promotionType, itemIds);
    }

    private static List<JsonNode> array(JsonNode arr) {
        List<JsonNode> out = new ArrayList<>();
        if (arr != null && arr.isArray()) arr.forEach(out::add);
        return out;
    }
}
