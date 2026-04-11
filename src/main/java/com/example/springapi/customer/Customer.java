package com.example.springapi.customer;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * JPA entity representing a customer stored in the {@code customer} table.
 *
 * <h3>Why not a Java record?</h3>
 * <p>JPA requires:
 * <ul>
 *   <li>A no-arg constructor (for reflective instantiation by the JPA provider).</li>
 *   <li>Mutable fields (JPA sets the generated ID after {@code persist}).</li>
 * </ul>
 * Java records are immutable and have no no-arg constructor, so they cannot be used as
 * JPA entities. Lombok's {@code @NoArgsConstructor} and {@code @AllArgsConstructor} together
 * with {@code @Getter}/{@code @Setter} generate the boilerplate that records would otherwise
 * eliminate.
 *
 * <h3>ID generation</h3>
 * <p>{@code GenerationType.IDENTITY} delegates ID generation to the PostgreSQL
 * {@code SERIAL} / {@code BIGSERIAL} column, which is the most efficient strategy for
 * single-database deployments. For distributed or multi-tenant scenarios, consider
 * {@code GenerationType.UUID} (Java 21 / Hibernate 6+).
 *
 * <p>The schema is created by Flyway (see {@code db/migration/V1__init.sql}).
 */
// JPA requires a mutable class with a no-arg constructor — records are not supported
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Customer {

    /** Database-generated primary key — assigned by PostgreSQL SERIAL after INSERT. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String email;
}
