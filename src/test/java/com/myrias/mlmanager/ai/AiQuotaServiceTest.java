package com.myrias.mlmanager.ai;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Check do teto de gasto — sem Spring nem banco. */
class AiQuotaServiceTest {

    private final LocalDate[] hoje = {LocalDate.of(2026, 7, 30)};

    /** 3 perguntas/dia por chave, US$ 0.10 por chave, US$ 0.25 global. */
    private AiQuotaService quota(AiCommandLogRepository repo) {
        return new AiQuotaService(repo, 3, new BigDecimal("0.10"),
                new BigDecimal("0.25"), () -> hoje[0]);
    }

    private AiQuotaService quota() {
        AiCommandLogRepository repo = mock(AiCommandLogRepository.class);
        when(repo.sumCostSince(any())).thenReturn(BigDecimal.ZERO);
        return quota(repo);
    }

    private static void assert429(Runnable call) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, call::run);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatusCode());
    }

    @Test
    void limiteDePerguntasPorChaveFecha() {
        AiQuotaService quota = quota();
        for (int i = 0; i < 3; i++) quota.require("ip:1.2.3.4");
        assert429(() -> quota.require("ip:1.2.3.4"));
        // Outro visitante não é punido pelo primeiro.
        assertDoesNotThrow(() -> quota.require("ip:9.9.9.9"));
    }

    @Test
    void limiteDeCustoPorChaveFecha() {
        AiQuotaService quota = quota();
        quota.require("ip:1.2.3.4");
        quota.record("ip:1.2.3.4", new BigDecimal("0.10"));
        assert429(() -> quota.require("ip:1.2.3.4"));
        assertDoesNotThrow(() -> quota.require("ip:9.9.9.9"));
    }

    @Test
    void estouraCapGlobalBloqueiaTodasAsChaves() {
        AiQuotaService quota = quota();
        quota.require("ip:1.2.3.4");
        quota.record("ip:1.2.3.4", new BigDecimal("0.25"));
        assert429(quota::requireGlobal);
        // Chave virgem também é barrada: o orçamento do dia acabou pra todos.
        assert429(() -> quota.require("ip:novo"));
    }

    @Test
    void viradaDoDiaZeraContador() {
        AiQuotaService quota = quota();
        for (int i = 0; i < 3; i++) quota.require("ip:1.2.3.4");
        quota.record("ip:1.2.3.4", new BigDecimal("0.25"));
        assert429(() -> quota.require("ip:1.2.3.4"));

        hoje[0] = hoje[0].plusDays(1);

        assertDoesNotThrow(() -> quota.require("ip:1.2.3.4"));
        assertDoesNotThrow(quota::requireGlobal);
    }

    /** Restart no meio do dia não pode zerar a fatura já gasta. */
    @Test
    void seedRecuperaOGastoDoDia() {
        AiCommandLogRepository repo = mock(AiCommandLogRepository.class);
        when(repo.sumCostSince(any())).thenReturn(new BigDecimal("0.25"));
        AiQuotaService quota = quota(repo);
        quota.seed();
        assert429(quota::requireGlobal);
    }

    /** Banco fora do ar no boot não derruba a aplicação. */
    @Test
    void seedComBancoQuebradoNaoDerrubaOBoot() {
        AiCommandLogRepository repo = mock(AiCommandLogRepository.class);
        when(repo.sumCostSince(any())).thenThrow(new IllegalStateException("sem banco"));
        AiQuotaService quota = quota(repo);
        assertDoesNotThrow(quota::seed);
        assertDoesNotThrow(() -> quota.require("ip:1.2.3.4"));
    }
}
