package com.hrb.mlmanager.ops;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistência dos logs de operação. As leituras/filtros entram na fatia de logs. */
public interface OperationLogRepository extends JpaRepository<OperationLog, Long> {
}
