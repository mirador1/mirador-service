package com.mirador.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * Case-insensitive substring search on name + description. Mirrors
     * the Customer search pattern : single {@code search} parameter,
     * 300 ms debounced from the UI side. Returns an empty page when
     * {@code search} is null/blank — the controller then falls back to
     * the unfiltered {@link #findAll(Pageable)}.
     */
    @Query("SELECT p FROM Product p " +
            "WHERE LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%')) " +
            "   OR LOWER(p.description) LIKE LOWER(CONCAT('%', :search, '%'))")
    Page<Product> search(@Param("search") String search, Pageable pageable);
}
