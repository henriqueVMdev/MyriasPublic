package com.myrias.mlmanager.ai;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiMemoryRepository extends JpaRepository<AiMemory, Long> {
    List<AiMemory> findAllByOrderByUpdatedAtDesc();
    List<AiMemory> findByEnabledTrueOrderByUpdatedAtDesc();
}
