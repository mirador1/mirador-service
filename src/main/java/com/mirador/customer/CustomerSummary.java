package com.mirador.customer;

/**
 * Spring Data interface projection for lightweight customer reads.
 *
 * <p>When this projection is used as the return type of a repository method, Spring Data JPA
 * generates a {@code SELECT id, name FROM customer} query — not {@code SELECT *}.
 * The JPA provider returns a proxy implementing this interface, with each getter backed by
 * the corresponding column value.
 *
 * <p>Why projections matter:
 * <ul>
 *   <li>Reduces the amount of data transferred from the DB (useful for wide tables).</li>
 *   <li>Avoids constructing a full entity graph (no lazy-loading pitfalls).</li>
 *   <li>No mapping code needed — Spring Data infers the column from the getter name.</li>
 * </ul>
 *
 * <p>Used by {@code GET /customers/summary}.
 *
 * [Spring Data JPA — interface projection]
 */
public interface CustomerSummary {

    /** Returns the customer's primary key. */
    Long getId();

    /** Returns the customer's display name. */
    String getName();
}
