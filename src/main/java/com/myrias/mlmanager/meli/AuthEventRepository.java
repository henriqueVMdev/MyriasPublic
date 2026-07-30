package com.myrias.mlmanager.meli;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Histórico de tentativas OAuth, mais recentes primeiro. */
public interface AuthEventRepository extends JpaRepository<AuthEvent, Long> {

    /** get_last_auth_events: passe {@code PageRequest.of(0, limit)}. */
    List<AuthEvent> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
