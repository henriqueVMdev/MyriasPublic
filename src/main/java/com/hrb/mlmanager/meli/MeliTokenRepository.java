package com.hrb.mlmanager.meli;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Queries de token ML. O Spring Data gera tudo a partir do nome do método —
 * substitui os select/update manuais do meli_auth.py.
 */
public interface MeliTokenRepository extends JpaRepository<MeliToken, Long> {

    Optional<MeliToken> findByUserId(Long userId);

    /** Conta ativa (deve haver no máximo uma marcada). */
    Optional<MeliToken> findFirstByActiveTrue();

    /** list_accounts: ativa primeiro, depois por ordem de criação. */
    List<MeliToken> findAllByOrderByActiveDescCreatedAtAsc();
}
