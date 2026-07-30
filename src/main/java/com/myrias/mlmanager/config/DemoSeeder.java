package com.myrias.mlmanager.config;

import com.myrias.mlmanager.auth.Permissions;
import com.myrias.mlmanager.auth.UserAccountService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Deploy público de demonstração: cria o usuário compartilhado `demo/demo`.
 *
 * Criar esse usuário também é o que mantém a janela de bootstrap do AppAuthFilter
 * fechada — com o banco vazio ela liberaria todo /api/* sem autenticação.
 */
@Component
@ConditionalOnProperty(name = "app.demo-mode", havingValue = "true")
public class DemoSeeder implements CommandLineRunner {

    private final UserAccountService users;

    public DemoSeeder(UserAccountService users) {
        this.users = users;
    }

    @Override
    public void run(String... args) {
        if (users.count() > 0) return;
        // NÃO-admin de propósito: PanelSecurity.require deixa admin furar toda
        // checagem de permissão, inclusive as que usamos como proteção aqui.
        // Só SECTIONS (páginas de leitura) — nenhuma ACTIONS (bulk_edit,
        // manage_promotions, manage_accounts...) e nenhuma METRICS (faturamento).
        users.create("demo", "demo", "Visitante (demo)", false, Permissions.SECTIONS);
        System.out.println(">> [demo] usuário demo/demo criado (somente leitura)");
    }
}
