package com.hrb.mlmanager.config;

import com.hrb.mlmanager.auth.Permissions;
import com.hrb.mlmanager.auth.UserAccountService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Só no profile "dev": cria admin/admin se não houver nenhum usuário, pra você
 * conseguir logar logo de cara com o banco H2 em memória. Em produção o admin
 * é criado à mão (equivalente ao scripts/create_admin.py).
 */
@Component
@Profile("dev")
public class DevSeeder implements CommandLineRunner {

    private final UserAccountService users;

    public DevSeeder(UserAccountService users) {
        this.users = users;
    }

    @Override
    public void run(String... args) {
        if (users.count() == 0) {
            users.create("admin", "admin", "Admin (dev)", true, Permissions.SECTIONS);
            System.out.println(">> [dev] usuário admin/admin criado");
        }
    }
}
