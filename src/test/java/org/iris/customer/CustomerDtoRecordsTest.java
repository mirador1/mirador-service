package org.iris.customer;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sanity tests for the immutable record DTOs that aren't instantiated
 * by any other test (EnrichedCustomerDto, CustomerDtoV2). Records get
 * generated accessors + equals/hashCode/toString from the compiler ;
 * a trivial round-trip test covers them all.
 */
class CustomerDtoRecordsTest {

    @Test
    void enrichedCustomerDto_carriesAllFieldsThroughAccessors() {
        EnrichedCustomerDto dto = new EnrichedCustomerDto(
                42L, "Alice Martin", "alice@example.com",
                "Alice Martin <alice@example.com>");

        assertThat(dto.id()).isEqualTo(42L);
        assertThat(dto.name()).isEqualTo("Alice Martin");
        assertThat(dto.email()).isEqualTo("alice@example.com");
        assertThat(dto.displayName()).isEqualTo("Alice Martin <alice@example.com>");
    }

    @Test
    void enrichedCustomerDto_hasValueEquality() {
        var a = new EnrichedCustomerDto(1L, "n", "e", "d");
        var b = new EnrichedCustomerDto(1L, "n", "e", "d");
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a.toString()).contains("EnrichedCustomerDto");
    }

    @Test
    void customerDtoV2_carriesAllFieldsThroughAccessors() {
        Instant now = Instant.parse("2026-04-29T00:00:00Z");
        CustomerDtoV2 dto = new CustomerDtoV2(7L, "Bob", "bob@example.com", now);

        assertThat(dto.id()).isEqualTo(7L);
        assertThat(dto.name()).isEqualTo("Bob");
        assertThat(dto.email()).isEqualTo("bob@example.com");
        assertThat(dto.createdAt()).isEqualTo(now);
    }

    @Test
    void customerDtoV2_hasValueEquality() {
        Instant now = Instant.parse("2026-04-29T00:00:00Z");
        var a = new CustomerDtoV2(1L, "n", "e", now);
        var b = new CustomerDtoV2(1L, "n", "e", now);
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }
}
