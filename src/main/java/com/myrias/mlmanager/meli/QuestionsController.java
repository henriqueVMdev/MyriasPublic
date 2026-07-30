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
@RequestMapping("/api/questions")
public class QuestionsController {

    private final MeliQuestionsService service;
    private final PanelSecurity security;

    public QuestionsController(MeliQuestionsService service, PanelSecurity security) {
        this.service = service;
        this.security = security;
    }

    @GetMapping("")
    public Map<String, Object> listQuestions(
            @RequestParam(defaultValue = "UNANSWERED") String status,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "7") int answered_since_days,
            HttpServletRequest request) {
        security.require(request, "perguntas");
        int safeLimit = Math.max(1, Math.min(limit, 50));
        int safeDays = Math.max(1, Math.min(answered_since_days, 365));
        return service.listQuestions(status, safeLimit, safeDays);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(
            @RequestParam(defaultValue = "day") String period,
            @RequestParam(defaultValue = "30") int periods,
            HttpServletRequest request) {
        security.require(request, "perguntas");
        if (!period.equals("day") && !period.equals("month")) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "period deve ser day ou month");
        }
        return service.stats(period, Math.max(3, Math.min(periods, 60)));
    }

    @PostMapping("/{questionId}/answer")
    public JsonNode answerQuestion(@PathVariable long questionId,
                                   @RequestBody JsonNode body,
                                   HttpServletRequest request) {
        security.require(request, "reply_questions");
        String text = body.path("text").asText("").trim();
        long accountUserId = body.path("account_user_id").asLong(0);
        if (text.isBlank() || accountUserId == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text e account_user_id sao obrigatorios");
        }
        return service.answerQuestion(questionId, text, accountUserId,
                body.path("item_id").asText(null), body.path("question_text").asText(null));
    }
}
