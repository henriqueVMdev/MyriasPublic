package com.myrias.mlmanager.ops;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

/**
 * Persistência e leitura dos logs de operação. Espelho das queries de
 * backend/app/api/logs.py.
 *
 * Divergência deliberada do Python: lá {@code item_ids} é uma coluna ARRAY do
 * Postgres e os endpoints de agrupamento usam SQL específico (unnest, array_agg,
 * AT TIME ZONE). Aqui {@code item_ids} é JSON em coluna texto (portável H2/Postgres,
 * ver REFACTOR.md), então o agrupamento por {@code batch_id}/item roda em memória
 * no {@link OperationLogService}. O {@link JpaSpecificationExecutor} cobre os
 * filtros dinâmicos por coluna real (operation_type, status, actor).
 */
public interface OperationLogRepository
        extends JpaRepository<OperationLog, Long>, JpaSpecificationExecutor<OperationLog> {

    /** Últimas 10 operações — alimenta o bloco "recent" do /stats. */
    List<OperationLog> findTop10ByOrderByCreatedAtDesc();

    /** Todas as linhas de um batch (página de detalhe). */
    List<OperationLog> findByBatchIdOrderByCreatedAtAsc(String batchId);

    /** Actors distintos (não nulos) para o filtro por usuário no Histórico. */
    @Query("select distinct o.actor from OperationLog o where o.actor is not null order by o.actor")
    List<String> findDistinctActors();

    /** Contagem por tipo de operação (stats). Retorna pares [operationType, count]. */
    @Query("select o.operationType, count(o) from OperationLog o group by o.operationType")
    List<Object[]> countByOperationType();

    /** Contagem por status (stats). Retorna pares [status, count]. */
    @Query("select o.status, count(o) from OperationLog o group by o.status")
    List<Object[]> countByStatus();
}
