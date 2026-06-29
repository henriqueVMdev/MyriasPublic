package com.hrb.mlmanager.meli;

import org.springframework.data.jpa.repository.JpaRepository;

/** PKCE verifier por state. Chave primária é o próprio {@code state} (String). */
public interface PkceStateRepository extends JpaRepository<PkceState, String> {
}
