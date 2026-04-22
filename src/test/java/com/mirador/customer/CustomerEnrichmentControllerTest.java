package com.mirador.customer;

import com.mirador.integration.BioService;
import com.mirador.integration.TodoItem;
import com.mirador.integration.TodoService;
import com.mirador.messaging.CustomerEnrichReply;
import com.mirador.messaging.CustomerEnrichRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.requestreply.KafkaReplyTimeoutException;
import org.springframework.kafka.requestreply.ReplyingKafkaTemplate;
import org.springframework.kafka.requestreply.RequestReplyFuture;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CustomerEnrichmentController} covering the three
 * external-system endpoints (bio / todos / enrich) extracted from
 * {@code CustomerController} on 2026-04-22 (Phase B-7-7).
 *
 * <p>Each test mocks the corresponding integration service so no Spring
 * context, Ollama, JSONPlaceholder, or Kafka broker is needed. Runs in
 * milliseconds.
 *
 * <p>The {@code enrich()} tests focus on
 * {@link CustomerEnrichmentController#classifyEnrichExecutionException}'s
 * routing — Kafka timeout vs other ExecutionException causes — since
 * that's the helper extracted with the controller and the part that's
 * NOT covered by integration tests.
 */
class CustomerEnrichmentControllerTest {

    private CustomerService service;
    private TodoService todoService;
    private BioService bioService;
    private ReplyingKafkaTemplate<String, CustomerEnrichRequest, CustomerEnrichReply> kafkaTemplate;
    private CustomerEnrichmentController controller;

    private static final CustomerDto SAMPLE_CUSTOMER =
            new CustomerDto(42L, "Alice", "alice@example.com");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = mock(CustomerService.class);
        todoService = mock(TodoService.class);
        bioService = mock(BioService.class);
        kafkaTemplate = mock(ReplyingKafkaTemplate.class);
        MeterRegistry meterRegistry = new SimpleMeterRegistry();

        controller = new CustomerEnrichmentController(
                service,
                todoService,
                bioService,
                ObservationRegistry.NOOP,
                kafkaTemplate,
                "customer.request",
                5L,
                meterRegistry);
    }

    // ── /bio ───────────────────────────────────────────────────────────────────

    @Test
    void generateBio_existingCustomer_returnsBioFromBioService() {
        when(service.findById(42L)).thenReturn(Optional.of(SAMPLE_CUSTOMER));
        when(bioService.generateBio(SAMPLE_CUSTOMER)).thenReturn("Alice is a senior architect.");

        var result = controller.generateBio(42L);

        assertThat(result).containsEntry("bio", "Alice is a senior architect.");
    }

    @Test
    void generateBio_unknownCustomer_throwsNotFound() {
        when(service.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.generateBio(99L))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("99");
    }

    // ── /todos ─────────────────────────────────────────────────────────────────

    @Test
    void getTodos_existingCustomer_delegatesToTodoService() {
        when(service.findById(42L)).thenReturn(Optional.of(SAMPLE_CUSTOMER));
        TodoItem t = new TodoItem(1L, 42L, "Buy milk", false);
        when(todoService.getTodos(42L)).thenReturn(List.of(t));

        List<TodoItem> result = controller.getTodos(42L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("Buy milk");
    }

    @Test
    void getTodos_unknownCustomer_throwsNotFound() {
        when(service.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getTodos(99L))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("99");
    }

    // ── /enrich ────────────────────────────────────────────────────────────────

    @Test
    void enrich_unknownCustomer_throwsNotFound() {
        when(service.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.enrich(99L))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("99");
    }

    @Test
    void enrich_kafkaTimeout_classifiedAsKafkaTimeoutWithTimeoutCause() throws Exception {
        // Real-world scenario: enrich consumer down → KafkaReplyTimeoutException
        // wrapped in ExecutionException by the future. The classifier MUST extract
        // it and re-throw as IllegalStateException("kafka-timeout") with a
        // TimeoutException cause so the @ControllerAdvice maps it to 504 (per
        // the @ApiResponse on the enrich endpoint).
        when(service.findById(42L)).thenReturn(Optional.of(SAMPLE_CUSTOMER));
        var kafkaTimeout = new KafkaReplyTimeoutException("Reply timeout");
        var executionException = new ExecutionException(kafkaTimeout);
        @SuppressWarnings("unchecked")
        var future = (RequestReplyFuture<String, CustomerEnrichRequest, CustomerEnrichReply>)
                mock(RequestReplyFuture.class);
        var failed = new CompletableFuture<org.apache.kafka.clients.consumer.ConsumerRecord<String, CustomerEnrichReply>>();
        failed.completeExceptionally(executionException);
        doThrow(executionException).when(future).get();
        when(kafkaTemplate.sendAndReceive(any(ProducerRecord.class), any(java.time.Duration.class)))
                .thenReturn(future);

        assertThatThrownBy(() -> controller.enrich(42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("kafka-timeout")
                .hasCauseInstanceOf(TimeoutException.class);
    }

    @Test
    void enrich_otherExecutionException_classifiedAsGenericEnrichFailure() throws Exception {
        // ExecutionException with a NON-KafkaReplyTimeoutException cause should
        // route to the "Enrich failed for id=X" path (mapped by the
        // @ControllerAdvice to 500). Guards against the classifier accidentally
        // labelling EVERY ExecutionException as kafka-timeout.
        when(service.findById(42L)).thenReturn(Optional.of(SAMPLE_CUSTOMER));
        var otherCause = new RuntimeException("serializer broke");
        var executionException = new ExecutionException(otherCause);
        @SuppressWarnings("unchecked")
        var future = (RequestReplyFuture<String, CustomerEnrichRequest, CustomerEnrichReply>)
                mock(RequestReplyFuture.class);
        doThrow(executionException).when(future).get();
        when(kafkaTemplate.sendAndReceive(any(ProducerRecord.class), any(java.time.Duration.class)))
                .thenReturn(future);

        assertThatThrownBy(() -> controller.enrich(42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Enrich failed for id=42")
                .hasCause(otherCause);
    }
}
