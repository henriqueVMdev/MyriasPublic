package com.hrb.mlmanager.auth;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Equivale ao UserService de leitura do FastAPI — o Spring Data gera as queries
 * a partir do nome do método (findBy..., count...).
 */
public interface UserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsername(String username);

    List<AppUser> findByActiveTrueOrderByUsername();
}
