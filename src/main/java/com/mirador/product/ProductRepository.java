package com.mirador.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link Product}.
 *
 * <p>Standard CRUD via {@link JpaRepository} (findAll / findById / save /
 * delete). The unique-name constraint is enforced at the DB level (V7
 * migration) — the application surfaces violations via
 * {@link org.springframework.dao.DataIntegrityViolationException}.
 *
 * <p>Custom queries kept minimal here ; rich filtering belongs in a
 * service layer + Specification or Querydsl as the API surface grows.
 */
@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * Lookup by unique name. Used at create-time to guard against
     * duplicate-name conflicts before issuing the INSERT (cheaper than
     * catching the integrity violation after the fact).
     */
    Optional<Product> findByName(String name);
}
