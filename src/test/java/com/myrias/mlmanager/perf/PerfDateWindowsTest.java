package com.myrias.mlmanager.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * As janelas de Ads (≤90 dias) precisam ser contíguas e SEM sobreposição —
 * somar métricas de janelas que compartilham datas dobraria o custo de ads.
 */
class PerfDateWindowsTest {

    @Test
    void windowsAreContiguousAndNonOverlapping() {
        LocalDate end = LocalDate.parse("2026-06-29");
        List<String[]> w = MeliPerformanceService.dateWindows(end, 180, 90);

        // 180 / 90 = 2 janelas
        assertEquals(2, w.size());
        // primeira janela termina no end
        assertEquals(end.toString(), w.get(0)[1]);

        // sem sobreposição: cada início da janela seguinte é anterior ao fim da anterior
        for (int i = 1; i < w.size(); i++) {
            LocalDate prevStart = LocalDate.parse(w.get(i - 1)[0]);
            LocalDate curEnd = LocalDate.parse(w.get(i)[1]);
            assertTrue(curEnd.isBefore(prevStart), "janela " + i + " não pode tocar a anterior");
            // contígua: exatamente 1 dia de gap (curEnd = prevStart - 1)
            assertEquals(prevStart.minusDays(1), curEnd);
        }
        // cada janela respeita o teto de 90 dias
        for (String[] win : w) {
            long span = LocalDate.parse(win[1]).toEpochDay() - LocalDate.parse(win[0]).toEpochDay();
            assertTrue(span <= 90, "janela maior que 90 dias: " + span);
        }
    }
}
