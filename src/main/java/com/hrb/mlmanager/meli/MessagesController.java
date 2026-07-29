package com.hrb.mlmanager.meli;

import com.fasterxml.jackson.databind.JsonNode;
import com.hrb.mlmanager.auth.PanelSecurity;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/messages")
public class MessagesController {

    private final MeliMessagesService service;
    private final PanelSecurity security;

    public MessagesController(MeliMessagesService service, PanelSecurity security) {
        this.service = service;
        this.security = security;
    }

    @GetMapping("/unread")
    public Map<String, Object> listUnread(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int enrich,
            HttpServletRequest request) {
        security.require(request, "mensagens");
        return service.listUnread(Math.max(1, Math.min(limit, 50)), enrich == 1);
    }

    @GetMapping("/packs/{packId}")
    public Map<String, Object> getThread(@PathVariable String packId,
                                         @RequestParam long account_user_id,
                                         HttpServletRequest request) {
        security.require(request, "mensagens");
        return service.getThread(packId, account_user_id, true);
    }

    @PostMapping("/packs/{packId}/reply")
    public JsonNode reply(@PathVariable String packId,
                          @RequestBody JsonNode body,
                          HttpServletRequest request) {
        security.require(request, "reply_messages");
        String text = body.path("text").asText("").trim();
        long accountUserId = body.path("account_user_id").asLong(0);
        if (text.isBlank() || accountUserId == 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Texto e conta do Mercado Livre são obrigatórios.");
        }
        if (text.length() > MeliMessagesService.DEFAULT_SELLER_MAX_MESSAGE_LENGTH) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "A mensagem ultrapassa o limite de "
                            + MeliMessagesService.DEFAULT_SELLER_MAX_MESSAGE_LENGTH + " caracteres.");
        }
        long buyer = body.path("buyer_user_id").asLong(0);
        Long buyerUserId = buyer == 0 ? null : buyer;
        try {
            return service.sendMessage(packId, accountUserId, text, buyerUserId);
        } catch (MeliMessagesService.MeliMessageSendException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
