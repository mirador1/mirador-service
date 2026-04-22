package com.mirador.customer;

import com.mirador.integration.BioService;
import com.mirador.integration.TodoItem;
import com.mirador.integration.TodoService;
import com.mirador.messaging.CustomerEnrichReply;
import com.mirador.messaging.CustomerEnrichRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.requestreply.KafkaReplyTimeoutException;
import org.springframework.kafka.requestreply.ReplyingKafkaTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "External-data enrichment" endpoints for {@code /customers} — extracted
 * from {@link CustomerController} 2026-04-22 under Phase B-7-7
 * follow-through. Three endpoints all augment a customer with content
 * computed by an external system:
 *
 * <ul>
 *   <li>{@code GET /customers/{id}/bio} — Spring AI / Ollama LLM-generated
 *       professional bio (Resilience4j circuit-breaker + retry).</li>
 *   <li>{@code GET /customers/{id}/todos} — JSONPlaceholder external API
 *       fetch (circuit-breaker + retry + empty-list fallback).</li>
 *   <li>{@code GET /customers/{id}/enrich} — synchronous Kafka request-reply
 *       (Pattern 2): publishes to {@code customer.request}, blocks on the
 *       reply from {@code customer.reply}.</li>
 * </ul>
 *
 * <p>Co-located on {@code /customers} via {@link RequestMapping} — Spring
 * tolerates multiple controllers mapping the same base path.
 *
 * <p>Why a separate controller: shared theme is "I/O against an external
 * system that may fail" (LLM, HTTP API, broker). Scoping to one file
 * makes the resilience patterns + their dependencies visible at a glance,
 * separate from the CRUD/reporting endpoints in {@link CustomerController}
 * and the demo-only diagnostics in {@link CustomerDiagnosticsController}.
 *
 * <p>CustomerController shrinks 705 → ~565 LOC after this split (-20%).
 */
@Tag(name = "Customers — enrichment",
     description = "External-system enrichment (LLM bio, JSONPlaceholder todos, Kafka request-reply enrich) — circuit-breaker / retry / fallback patterns.")
@RestController
@RequestMapping(CustomerController.PATH_CUSTOMERS)
public class CustomerEnrichmentController {

    private static final Logger log = LoggerFactory.getLogger(CustomerEnrichmentController.class);
    private static final String KEY_ENDPOINT  = "endpoint";
    private static final String ERR_NOT_FOUND = "Customer not found: ";

    private final CustomerService service;
    private final TodoService todoService;
    private final BioService bioService;
    private final ObservationRegistry observationRegistry;
    private final ReplyingKafkaTemplate<String, CustomerEnrichRequest, CustomerEnrichReply> replyingKafkaTemplate;
    private final String customerRequestTopic;
    private final long enrichTimeoutSeconds;
    private final Timer customerEnrichTimer;

    public CustomerEnrichmentController(
            CustomerService service,
            TodoService todoService,
            BioService bioService,
            ObservationRegistry observationRegistry,
            ReplyingKafkaTemplate<String, CustomerEnrichRequest, CustomerEnrichReply> replyingKafkaTemplate,
            @Value("${app.kafka.topics.customer-request}") String customerRequestTopic,
            @Value("${app.kafka.enrich-timeout-seconds}") long enrichTimeoutSeconds,
            MeterRegistry meterRegistry) {
        this.service = service;
        this.todoService = todoService;
        this.bioService = bioService;
        this.observationRegistry = observationRegistry;
        this.replyingKafkaTemplate = replyingKafkaTemplate;
        this.customerRequestTopic = customerRequestTopic;
        this.enrichTimeoutSeconds = enrichTimeoutSeconds;
        this.customerEnrichTimer = Timer.builder("customer.enrich.duration")
                .description("Duration of POST /customers/{id}/enrich (Kafka request-reply)")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    @Operation(summary = "Generate AI bio (Ollama LLM)",
            description = "Calls the local Ollama LLM (llama3.2) via Spring AI to generate a professional bio for the customer. "
                    + "Protected by a Resilience4j **circuit breaker** + **retry** — returns 503 when the circuit is open. "
                    + "Response time: 1–10 s depending on model and hardware.")
    @ApiResponse(responseCode = "200", description = "Bio generated — `{\"bio\": \"...\"}` ")
    @ApiResponse(responseCode = "404", description = "Customer not found", content = @Content)
    @ApiResponse(responseCode = "503", description = "Ollama unavailable or circuit breaker open", content = @Content)
    @GetMapping("/{id}/bio")
    public Map<String, String> generateBio(
            @Parameter(description = "Customer ID", example = "3") @PathVariable Long id) {
        CustomerDto customer = service.findById(id)
                .orElseThrow(() -> new NoSuchElementException(ERR_NOT_FOUND + id));
        return Map.of("bio", bioService.generateBio(customer));
    }

    @Operation(summary = "Get todos from JSONPlaceholder (external API)",
            description = "Fetches todos for the customer from the external JSONPlaceholder API (https://jsonplaceholder.typicode.com). "
                    + "Decorated with Resilience4j **circuit breaker** + **retry** + fallback (empty list). "
                    + "Demonstrates graceful degradation — never returns 5xx even if the external API is down.")
    @ApiResponse(responseCode = "200", description = "List of todos (may be empty if circuit is open or API is down)")
    @ApiResponse(responseCode = "404", description = "Customer not found", content = @Content)
    @GetMapping("/{id}/todos")
    public List<TodoItem> getTodos(
            @Parameter(description = "Customer ID", example = "3") @PathVariable Long id) {
        service.findById(id)
                .orElseThrow(() -> new NoSuchElementException(ERR_NOT_FOUND + id));
        return todoService.getTodos(id);
    }

    @Operation(summary = "Enrich customer via Kafka request-reply",
            description = "Sends a `CustomerEnrichRequest` to the `customer.request` Kafka topic and **blocks** until the consumer "
                    + "replies on `customer.reply` with a computed `displayName`. "
                    + "Demonstrates synchronous Kafka request-reply (not just fire-and-forget). "
                    + "Returns `504 Gateway Timeout` if no reply arrives within the configured timeout.")
    @ApiResponse(responseCode = "200", description = "Enriched customer with displayName")
    @ApiResponse(responseCode = "404", description = "Customer not found", content = @Content)
    @ApiResponse(responseCode = "504", description = "Kafka consumer did not reply within the timeout", content = @Content)
    @GetMapping("/{id}/enrich")
    public EnrichedCustomerDto enrich(
            @Parameter(description = "Customer ID", example = "3") @PathVariable Long id) {
        Observation obs = Observation.createNotStarted("customer.enrich", observationRegistry)
                .lowCardinalityKeyValue(KEY_ENDPOINT, "/customers/{id}/enrich");
        return obs.observe(() -> customerEnrichTimer.record(() -> {
            CustomerDto customer = service.findById(id)
                    .orElseThrow(() -> new NoSuchElementException(ERR_NOT_FOUND + id));

            var producerRecord = new ProducerRecord<>(customerRequestTopic,
                    String.valueOf(id),
                    new CustomerEnrichRequest(customer.id(), customer.name(), customer.email()));

            try {
                CustomerEnrichReply reply = replyingKafkaTemplate
                        .sendAndReceive(producerRecord, java.time.Duration.ofSeconds(enrichTimeoutSeconds))
                        .get()
                        .value();
                log.info("kafka_enrich_reply id={} displayName={}", id, reply.displayName());
                return new EnrichedCustomerDto(reply.id(), reply.name(), reply.email(), reply.displayName());
            } catch (InterruptedException e) {
                log.warn("kafka_enrich_interrupted id={} timeoutSec={}", id, enrichTimeoutSeconds);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for enrich reply", e);
            } catch (ExecutionException e) {
                throw classifyEnrichExecutionException(id, e);
            }
        }));
    }

    /**
     * Same logic as the original {@code CustomerController.classifyEnrichExecutionException}
     * extracted along with the {@code enrich()} endpoint to keep the helper co-located
     * with its only caller (per Sonar S3776 + SRP).
     */
    private IllegalStateException classifyEnrichExecutionException(Long id, ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof KafkaReplyTimeoutException) {
            log.warn("kafka_enrich_timeout id={} timeoutSec={} topic={}",
                    id, enrichTimeoutSeconds, customerRequestTopic);
            return new IllegalStateException("kafka-timeout",
                    new java.util.concurrent.TimeoutException("Kafka reply timed out for id=" + id));
        }
        String causeType = cause != null ? cause.getClass().getSimpleName() : "null";
        String causeMsg = cause != null ? cause.getMessage() : "null";
        log.error("kafka_enrich_failed id={} causeType={} causeMsg={}", id, causeType, causeMsg);
        return new IllegalStateException("Enrich failed for id=" + id, cause);
    }
}
