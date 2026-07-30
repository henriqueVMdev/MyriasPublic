package com.myrias.mlmanager;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test: sobe o ApplicationContext inteiro no H2 (perfil dev). Verifica o
 * wiring de todos os beans (filtros, controllers, MeliClient/RestClient,
 * @Value) e o mapeamento JPA das entidades contra um banco real em memória.
 */
@SpringBootTest
@ActiveProfiles("dev")
class MlManagerApplicationTests {

    @Test
    void contextLoads() {
    }
}
