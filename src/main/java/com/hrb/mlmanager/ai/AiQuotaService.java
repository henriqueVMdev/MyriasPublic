package com.hrb.mlmanager.ai;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Teto de gasto diário do assistente, checado ANTES de chamar o modelo.
 *
 * Contador em memória — e não uma query por request — de propósito: o custo só é
 * conhecido DEPOIS da resposta do OpenRouter, e a linha de auditoria só é gravada
 * no fim do comando inteiro (AiAuditService.Tracker.finish). Uma query SUM(cost)
 * por iteração leria sempre o mesmo total pré-comando, então as 8 chamadas do
 * mesmo loop passariam todas. Só um contador vivo vê o gasto em voo.
 *
 * ponytail: lock global e 1 instância. O gargalo real é a API do modelo, não este
 * mapa. Com réplicas, trocar por Redis — senão o teto "global" vira por-réplica,
 * que é justamente o modo de falhar caro.
 */
@Service
public class AiQuotaService {

    private static final Logger log = LoggerFactory.getLogger(AiQuotaService.class);
    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    private final AiCommandLogRepository repository;
    private final Supplier<LocalDate> today;
    private final int keyDailyRequests;
    private final BigDecimal keyDailyCost;
    private final BigDecimal globalDailyCost;

    private final Map<String, Bucket> buckets = new HashMap<>();
    private BigDecimal globalCost = BigDecimal.ZERO;
    private LocalDate day;

    @Autowired
    public AiQuotaService(
            AiCommandLogRepository repository,
            @Value("${openrouter.quota.key-daily-requests:20}") int keyDailyRequests,
            @Value("${openrouter.quota.key-daily-cost:0.50}") BigDecimal keyDailyCost,
            @Value("${openrouter.quota.global-daily-cost:5.00}") BigDecimal globalDailyCost) {
        this(repository, keyDailyRequests, keyDailyCost, globalDailyCost,
                () -> LocalDate.now(SAO_PAULO));
    }

    /** Construtor do teste: relógio injetável pra checar a virada do dia. */
    AiQuotaService(AiCommandLogRepository repository, int keyDailyRequests,
                   BigDecimal keyDailyCost, BigDecimal globalDailyCost,
                   Supplier<LocalDate> today) {
        this.repository = repository;
        this.keyDailyRequests = keyDailyRequests;
        this.keyDailyCost = keyDailyCost;
        this.globalDailyCost = globalDailyCost;
        this.today = today;
        this.day = today.get();
    }

    /**
     * Recupera o gasto do dia do banco pra o teto sobreviver a um restart.
     * Só o total global é recuperável: em modo demo a chave é o IP, que não é
     * persistido, então os baldes por chave começam zerados. O global é o que
     * protege a fatura, então é ele que precisa sobreviver.
     */
    @PostConstruct
    synchronized void seed() {
        try {
            BigDecimal spent = repository.sumCostSince(startOfDay());
            if (spent != null) globalCost = spent;
            log.info("Teto de IA: US$ {} gastos hoje de US$ {} (limite por chave: {} perguntas)",
                    globalCost.toPlainString(), globalDailyCost.toPlainString(), keyDailyRequests);
        } catch (Exception e) {
            // Não derruba o boot, mas o teto do dia pode ser furado até o próximo
            // restart — por isso é ERROR e não WARN.
            log.error("Não consegui semear o teto de gasto ({}). Começando de zero.",
                    e.getMessage());
        }
    }

    /** Pré-comando: 429 se a chave estourou o limite dela ou se o dia já fechou. */
    public synchronized void require(String key) {
        rollover();
        checkGlobal();
        Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket());
        if (bucket.requests >= keyDailyRequests) {
            throw tooMany("Você atingiu o limite de " + keyDailyRequests
                    + " perguntas por dia ao assistente. Tente novamente amanhã.");
        }
        if (bucket.cost.compareTo(keyDailyCost) >= 0) {
            throw tooMany("Você atingiu o limite de uso diário do assistente. "
                    + "Tente novamente amanhã.");
        }
        // Conta na entrada: sem isso, respostas que voltam com custo 0 seriam grátis
        // e o limite de perguntas nunca fecharia.
        bucket.requests++;
    }

    /** Dentro do loop do agente: só o teto global, pra um comando não comer o dia. */
    public synchronized void requireGlobal() {
        rollover();
        checkGlobal();
    }

    public synchronized void record(String key, BigDecimal cost) {
        if (cost == null || cost.signum() <= 0) return;
        rollover();
        globalCost = globalCost.add(cost);
        Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket());
        bucket.cost = bucket.cost.add(cost);
    }

    private void checkGlobal() {
        if (globalCost.compareTo(globalDailyCost) >= 0) {
            throw tooMany("O orçamento diário do assistente acabou. Tente novamente amanhã.");
        }
    }

    /** Virada de dia preguiçosa: um `if` na entrada, sem scheduler. */
    private void rollover() {
        LocalDate now = today.get();
        if (!now.equals(day)) {
            day = now;
            globalCost = BigDecimal.ZERO;
            buckets.clear();
        }
    }

    private Instant startOfDay() {
        return today.get().atStartOfDay(SAO_PAULO).toInstant();
    }

    private static ResponseStatusException tooMany(String detail) {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, detail);
    }

    private static final class Bucket {
        int requests;
        BigDecimal cost = BigDecimal.ZERO;
    }
}
