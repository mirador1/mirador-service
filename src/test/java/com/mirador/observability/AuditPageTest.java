package com.mirador.observability;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AuditPage} — the paginated wrapper for
 * GET /audit responses. The compact constructor uses
 * {@link List#copyOf(java.util.Collection)} to defensive-copy the
 * content list ; this test pins that behavior.
 */
class AuditPageTest {

    @Test
    void compactConstructor_defensiveCopiesContent_mutatingOriginalDoesNotAffectRecord() {
        // Pinned: the record's compact constructor calls List.copyOf
        // which produces an UNMODIFIABLE copy independent of the source.
        // Without it, a caller mutating their list AFTER constructing
        // the AuditPage would mutate the record's content too — surprise
        // behavior + data race in a multi-threaded request handler.
        List<AuditEventDto> mutable = new ArrayList<>();
        mutable.add(new AuditEventDto(1L, "admin", "LOGIN", "ok", "127.0.0.1", Instant.now()));

        AuditPage page = new AuditPage(mutable, 0, 20, 1, 1);

        // Mutate the ORIGINAL list AFTER construction
        mutable.add(new AuditEventDto(2L, "spy", "INJECTED", "bad", "1.2.3.4", Instant.now()));

        // The record's content is unchanged
        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).action()).isEqualTo("LOGIN");
    }

    @Test
    void compactConstructor_resultingContentIsImmutable_throwsOnMutation() {
        // Pinned: List.copyOf returns an UnmodifiableList. Attempts to
        // add/remove via page.content() throw — protects the record's
        // immutability contract. Without this, a downstream caller
        // could call page.content().add(...) and silently corrupt the
        // page's state.
        AuditPage page = new AuditPage(
                List.of(new AuditEventDto(1L, "admin", "LOGIN", "ok", "127.0.0.1", Instant.now())),
                0, 20, 1, 1);

        assertThatThrownBy(() -> page.content().add(
                new AuditEventDto(99L, "x", "x", "x", "x", Instant.now()))
        ).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void compactConstructor_acceptsEmptyList() {
        // Sanity : empty list is a valid input (no audit events match
        // the query). Should not throw.
        AuditPage page = new AuditPage(List.of(), 0, 20, 0, 0);

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
    }

    @Test
    void compactConstructor_metadataFieldsPassedThrough() {
        // Pinned: the 4 numeric fields (page/size/totalElements/totalPages)
        // are stored as-is — the compact constructor ONLY transforms
        // content. A regression that recomputed totalPages or otherwise
        // touched the metadata would break consumers that pre-compute
        // these for paging UI links.
        AuditPage page = new AuditPage(List.of(), 3, 25, 100, 4);

        assertThat(page.page()).isEqualTo(3);
        assertThat(page.size()).isEqualTo(25);
        assertThat(page.totalElements()).isEqualTo(100);
        assertThat(page.totalPages()).isEqualTo(4);
    }
}
