package com.hrb.mlmanager.ai;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AiCommandLogRepository
        extends JpaRepository<AiCommandLog, Long>, JpaSpecificationExecutor<AiCommandLog> {
}
