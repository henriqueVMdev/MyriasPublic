package com.hrb.mlmanager.ai;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiSkillRepository extends JpaRepository<AiSkill, Long> {
    List<AiSkill> findAllByOrderByUpdatedAtDesc();
    List<AiSkill> findByEnabledTrueOrderByUpdatedAtDesc();
}
