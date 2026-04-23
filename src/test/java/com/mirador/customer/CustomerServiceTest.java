package com.mirador.customer;

import com.mirador.customer.port.CustomerEventPort;
import com.mirador.observability.port.AuditEventPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CustomerService} — the heart of the customer
 * domain. Tests focus on the side-effect contract: every write goes
 * through repository + ports (event/audit) + SSE/WebSocket.
 *
 * <p>All collaborators mocked — no Spring context, no PostgreSQL,
 * no Kafka, no Redis. Each test runs in milliseconds.
 */
class CustomerServiceTest {

    private CustomerRepository repository;
    private RecentCustomerBuffer recentBuffer;
    private CustomerEventPort eventPort;
    private SimpMessagingTemplate websocket;
    private SseEmitterRegistry sseRegistry;
    private AuditEventPort auditPort;
    private CustomerService service;

    @BeforeEach
    void setUp() {
        repository = mock(CustomerRepository.class);
        recentBuffer = mock(RecentCustomerBuffer.class);
        eventPort = mock(CustomerEventPort.class);
        websocket = mock(SimpMessagingTemplate.class);
        sseRegistry = mock(SseEmitterRegistry.class);
        auditPort = mock(AuditEventPort.class);
        service = new CustomerService(repository, recentBuffer, eventPort,
                websocket, sseRegistry, auditPort);
    }

    private static Customer entity(Long id, String name, String email) {
        return new Customer(id, name, email, Instant.parse("2026-04-23T00:00:00Z"));
    }

    // ── findAll / findById ────────────────────────────────────────────────────

    @Test
    void findAll_mapsRepositoryPageToDtos() {
        Pageable pageable = PageRequest.of(0, 10);
        Customer c = entity(1L, "Alice", "alice@example.com");
        when(repository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(c)));

        Page<CustomerDto> result = service.findAll(pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).id()).isEqualTo(1L);
        assertThat(result.getContent().get(0).name()).isEqualTo("Alice");
    }

    @Test
    void findById_existing_returnsDto() {
        when(repository.findById(42L)).thenReturn(
                Optional.of(entity(42L, "Bob", "bob@example.com")));

        Optional<CustomerDto> result = service.findById(42L);

        assertThat(result).isPresent();
        assertThat(result.get().email()).isEqualTo("bob@example.com");
    }

    @Test
    void findById_unknown_returnsEmptyOptional() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThat(service.findById(99L)).isEmpty();
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    void create_persistsAndTriggersAllSideEffects() {
        var req = new CreateCustomerRequest("Alice", "alice@example.com");
        Customer saved = entity(1L, "Alice", "alice@example.com");
        when(repository.save(any(Customer.class))).thenReturn(saved);

        CustomerDto result = service.create(req);

        // 1. Persists
        verify(repository).save(any(Customer.class));
        // 2. Buffer
        verify(recentBuffer).add(any(CustomerDto.class));
        // 3. Kafka event (via port)
        verify(eventPort).publishCreated(1L, "Alice", "alice@example.com");
        // 4. WebSocket
        verify(websocket).convertAndSend(eq("/topic/customers"), any(Object.class));
        // 5. SSE
        verify(sseRegistry).send(eq("customer"), any(Object.class));
        // 6. Audit log
        verify(auditPort).recordEvent(anyString(), eq("CUSTOMER_CREATED"),
                anyString(), eq(null));
        // Returns DTO
        assertThat(result.id()).isEqualTo(1L);
    }

    // ── update ────────────────────────────────────────────────────────────────

    @Test
    void update_existingCustomer_updatesFieldsAndAudits() {
        Customer existing = entity(1L, "Old Name", "old@example.com");
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        CustomerDto result = service.update(1L,
                new CreateCustomerRequest("New Name", "new@example.com"));

        // The entity must be mutated BEFORE save (Hibernate detects via dirty
        // checking either way, but the explicit setters are the contract).
        assertThat(existing.getName()).isEqualTo("New Name");
        assertThat(existing.getEmail()).isEqualTo("new@example.com");
        verify(auditPort).recordEvent(anyString(), eq("CUSTOMER_UPDATED"),
                anyString(), eq(null));
        assertThat(result).isNotNull();
    }

    @Test
    void update_unknownCustomer_throwsNoSuchElement() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(99L,
                new CreateCustomerRequest("X", "x@y.z")))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("99");
        verify(repository, never()).save(any(Customer.class));
        verify(auditPort, never()).recordEvent(anyString(), anyString(),
                anyString(), anyString());
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    void delete_existingCustomer_deletesAndAudits() {
        when(repository.existsById(42L)).thenReturn(true);

        service.delete(42L);

        verify(repository).deleteById(42L);
        verify(auditPort).recordEvent(anyString(), eq("CUSTOMER_DELETED"),
                eq("id=42"), eq(null));
    }

    @Test
    void delete_unknownCustomer_throwsNoSuchElement_andDoesNotDelete() {
        when(repository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(99L))
                .isInstanceOf(NoSuchElementException.class);
        verify(repository, never()).deleteById(any(Long.class));
        verify(auditPort, never()).recordEvent(anyString(), anyString(),
                anyString(), anyString());
    }

    // ── patch ─────────────────────────────────────────────────────────────────

    @Test
    void patch_partialUpdate_appliesOnlyProvidedFields() {
        // Pinned: PATCH semantics. Sending {name: "X"} must NOT clear the
        // email — only update the name. The if (!isBlank()) guards in the
        // service implement this contract.
        Customer existing = entity(1L, "Original Name", "original@example.com");
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        service.patch(1L, new PatchCustomerRequest("New Name", null));

        assertThat(existing.getName()).isEqualTo("New Name");
        // Email NOT touched — original value preserved
        assertThat(existing.getEmail()).isEqualTo("original@example.com");
    }

    @Test
    void patch_blankFields_treatedAsOmitted() {
        // Same as null — service guards with `!isBlank()`.
        Customer existing = entity(1L, "Original", "original@example.com");
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        service.patch(1L, new PatchCustomerRequest("   ", "   "));

        assertThat(existing.getName()).isEqualTo("Original");
        assertThat(existing.getEmail()).isEqualTo("original@example.com");
    }

    @Test
    void patch_unknownCustomer_throwsNoSuchElement() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.patch(99L,
                new PatchCustomerRequest("X", null)))
                .isInstanceOf(NoSuchElementException.class);
    }

    // ── findAllCursor ─────────────────────────────────────────────────────────

    @Test
    void findAllCursor_lessThanPlusOneItems_hasNextFalse() {
        // size=10, fetched 8 → less than 11 (size+1) so hasNext=false
        when(repository.findByIdGreaterThanOrderByIdAsc(eq(0L),
                any(Pageable.class)))
                .thenReturn(List.of(
                        entity(1L, "A", "a@x"), entity(2L, "B", "b@x")));

        CursorPage<CustomerDto> page = service.findAllCursor(0L, 10);

        assertThat(page.hasNext()).isFalse();
        assertThat(page.nextCursor()).isNull();
        assertThat(page.content()).hasSize(2);
    }

    @Test
    void findAllCursor_sizePlusOneItems_hasNextTrueAndCursorIsLastIdOfPage() {
        // size=2, fetched 3 → has next, return first 2, cursor = last id
        // of page (= ID of element 2 in fetched list)
        when(repository.findByIdGreaterThanOrderByIdAsc(eq(0L),
                any(Pageable.class)))
                .thenReturn(List.of(
                        entity(1L, "A", "a@x"),
                        entity(2L, "B", "b@x"),
                        entity(3L, "C", "c@x")));

        CursorPage<CustomerDto> page = service.findAllCursor(0L, 2);

        assertThat(page.hasNext()).isTrue();
        assertThat(page.content()).hasSize(2);
        assertThat(page.nextCursor()).isEqualTo(2L);
    }

    // ── batchCreate ───────────────────────────────────────────────────────────

    @Test
    void batchCreate_skipsDuplicateEmails_andCollectsErrors() {
        // Pinned: an email that already exists must produce a BatchError
        // (NOT abort the whole batch). Other rows still get created.
        var req1 = new CreateCustomerRequest("Alice", "alice@example.com");
        var req2 = new CreateCustomerRequest("Bob", "bob@example.com"); // dup

        when(repository.existsByEmail("alice@example.com")).thenReturn(false);
        when(repository.existsByEmail("bob@example.com")).thenReturn(true); // dup
        when(repository.save(any(Customer.class)))
                .thenReturn(entity(1L, "Alice", "alice@example.com"));

        BatchImportResult result = service.batchCreate(List.of(req1, req2));

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).reason()).isEqualTo("Email already exists");
    }

    @Test
    void batchCreate_individualSaveFailure_doesNotAbortBatch() {
        // Pinned: a runtime exception on row N must be caught + recorded as
        // BatchError, NOT propagated. Otherwise a single bad row breaks
        // the whole import — undesirable for a "best-effort" batch endpoint.
        var req1 = new CreateCustomerRequest("Alice", "alice@example.com");
        var req2 = new CreateCustomerRequest("Bob", "bob@example.com");

        when(repository.existsByEmail(anyString())).thenReturn(false);
        // First save succeeds, second throws
        when(repository.save(any(Customer.class)))
                .thenReturn(entity(1L, "Alice", "alice@example.com"))
                .thenThrow(new RuntimeException("DB constraint violation"));

        BatchImportResult result = service.batchCreate(List.of(req1, req2));

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors().get(0).reason()).contains("DB constraint violation");
    }

    // ── search ────────────────────────────────────────────────────────────────

    @Test
    void search_delegatesToRepositoryAndMapsToDtos() {
        Pageable pageable = PageRequest.of(0, 10);
        when(repository.search("alice", pageable))
                .thenReturn(new PageImpl<>(List.of(entity(1L, "Alice", "a@x"))));

        Page<CustomerDto> result = service.search("alice", pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).name()).isEqualTo("Alice");
    }

    // ── simulateSlowQuery ─────────────────────────────────────────────────────

    @Test
    void simulateSlowQuery_delegatesDirectlyToRepository() {
        service.simulateSlowQuery(2.5);

        verify(repository).simulateSlowQuery(2.5);
    }
}
