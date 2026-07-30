package com.myrias.mlmanager.perf;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Snapshots de performance por (conta, tipo). */
public interface PerfSnapshotRepository extends JpaRepository<PerfSnapshot, Long> {

    Optional<PerfSnapshot> findByUserIdAndKind(Long userId, String kind);
}
