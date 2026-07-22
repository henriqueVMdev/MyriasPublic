package com.hrb.mlmanager.competition;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hrb.mlmanager.auth.PanelSecurity;
import com.hrb.mlmanager.meli.MeliAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Análise de concorrência. Fase 1: detalhe ao vivo por item (catálogo). */
@RestController
@RequestMapping("/api/competition")
public class CompetitionController {

    private final MeliCompetitionService svc;
    private final MeliAuthService auth;
    private final PanelSecurity security;

    public CompetitionController(MeliCompetitionService svc, MeliAuthService auth, PanelSecurity security) {
        this.svc = svc;
        this.auth = auth;
        this.security = security;
    }

    @GetMapping("/items/{itemId}")
    public ObjectNode analyzeItem(@PathVariable String itemId, HttpServletRequest request) {
        security.require(request, "concorrencia");
        long userId = auth.requireActiveAccountId();
        return svc.analyzeItem(userId, itemId);
    }
}
