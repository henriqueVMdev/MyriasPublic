package com.hrb.mlmanager.auth;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Catálogo central de chaves de permissão (espelho de backend/app/permissions.py).
 * Seções controlam acesso a páginas; ações controlam operações destrutivas;
 * métricas escondem dado financeiro sensível.
 */
public final class Permissions {

    public static final List<String> SECTIONS = List.of(
            "dashboard", "performance", "repetidos", "bulk", "clone", "promocoes",
            "logs", "planilhas", "perguntas", "mensagens", "atendimento_historico",
            "assistente"
    );

    public static final List<String> ACTIONS = List.of(
            "delete_listing", "bulk_edit", "clone_listing", "manage_promotions",
            "reply_questions", "reply_messages", "manage_accounts"
    );

    public static final List<String> METRICS = List.of("dashboard_revenue");

    private static final Set<String> ALL = new LinkedHashSet<>() {{
        addAll(SECTIONS);
        addAll(ACTIONS);
        addAll(METRICS);
    }};

    private Permissions() {}

    /** Filtra/deduplica, mantendo só chaves conhecidas (ignora lixo). */
    public static List<String> valid(List<String> keys) {
        if (keys == null) return List.of();
        Set<String> out = new LinkedHashSet<>();
        for (String k : keys) {
            if (ALL.contains(k)) out.add(k);
        }
        return List.copyOf(out);
    }
}
