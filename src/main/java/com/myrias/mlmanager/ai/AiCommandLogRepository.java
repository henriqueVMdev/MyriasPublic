package com.hrb.mlmanager.ai;

import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiCommandLogRepository
        extends JpaRepository<AiCommandLog, Long>, JpaSpecificationExecutor<AiCommandLog> {

    /** Gasto do dia, lido uma única vez no startup pelo AiQuotaService. */
    @Query("select coalesce(sum(l.cost), 0) from AiCommandLog l where l.createdAt >= :since")
    BigDecimal sumCostSince(@Param("since") Instant since);
}
