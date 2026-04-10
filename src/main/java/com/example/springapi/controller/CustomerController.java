package com.example.springapi.controller;

import com.example.springapi.client.TodoItem;
import com.example.springapi.dto.CreateCustomerRequest;
import com.example.springapi.dto.CustomerDto;
import com.example.springapi.dto.EnrichedCustomerDto;
import com.example.springapi.event.CustomerEnrichReply;
import com.example.springapi.event.CustomerEnrichRequest;
import com.example.springapi.service.AggregationService;
import com.example.springapi.service.BioService;
import com.example.springapi.service.CustomerService;
import com.example.springapi.service.RecentCustomerBuffer;
import com.example.springapi.service.TodoService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.validation.Valid;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.requestreply.KafkaReplyTimeoutException;
import org.springframework.kafka.requestreply.ReplyingKafkaTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/customers")
public class CustomerController {

    private static final Logger log = LoggerFactory.getLogger(CustomerController.class);

    private final CustomerService service;
    private final RecentCustomerBuffer recentCustomerBuffer;
    private final AggregationService aggregationService;
    private final ObservationRegistry observationRegistry;
    private final ReplyingKafkaTemplate<String, CustomerEnrichRequest, CustomerEnrichReply> replyingKafkaTemplate;
    private final TodoService todoService;
    private final BioService bioService;
    private final String customerRequestTopic;
    private final long enrichTimeoutSeconds;
    private final Counter customerCreatedCounter;
    private final Timer customerCreateTimer;
    private final Timer customerFindAllTimer;
    private final Timer customerAggregateTimer;
    private final Timer customerEnrichTimer;

    public CustomerController(CustomerService service,
                              RecentCustomerBuffer recentCustomerBuffer,
                              AggregationService aggregationService,
                              ObservationRegistry observationRegistry,
                              ReplyingKafkaTemplate<String, CustomerEnrichRequest, CustomerEnrichReply> replyingKafkaTemplate,
                              TodoService todoService,
                              BioService bioService,
                              @Value("${app.kafka.topics.customer-request}") String customerRequestTopic,
                              @Value("${app.kafka.enrich-timeout-seconds}") long enrichTimeoutSeconds,
                              MeterRegistry meterRegistry) {
        this.service = service;
        this.recentCustomerBuffer = recentCustomerBuffer;
        this.aggregationService = aggregationService;
        this.observationRegistry = observationRegistry;
        this.replyingKafkaTemplate = replyingKafkaTemplate;
        this.todoService = todoService;
        this.bioService = bioService;
        this.customerRequestTopic = customerRequestTopic;
        this.enrichTimeoutSeconds = enrichTimeoutSeconds;
        this.customerCreatedCounter = Counter.builder("customer.created.count")
                .description("Number of customers created")
                .register(meterRegistry);
        this.customerCreateTimer = Timer.builder("customer.create.duration")
                .description("Duration of customer creation requests")
                .publishPercentileHistogram()
                .register(meterRegistry);
        this.customerFindAllTimer = Timer.builder("customer.find_all.duration")
                .description("Duration of customer list requests")
                .publishPercentileHistogram()
                .register(meterRegistry);
        this.customerAggregateTimer = Timer.builder("customer.aggregate.duration")
                .description("Duration of aggregate endpoint")
                .publishPercentileHistogram()
                .register(meterRegistry);
        this.customerEnrichTimer = Timer.builder("customer.enrich.duration")
                .description("Duration of Kafka request-reply enrich endpoint")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    @GetMapping
    public Page<CustomerDto> getAll(@PageableDefault(size = 20, sort = "id") Pageable pageable) {
        return Observation.createNotStarted("customer.find-all", observationRegistry)
                .lowCardinalityKeyValue("endpoint", "/customers")
                .observe(() -> customerFindAllTimer.record(() -> service.findAll(pageable)));
    }

    @PostMapping
    public CustomerDto create(@Valid @RequestBody CreateCustomerRequest request) {
        return Observation.createNotStarted("customer.create", observationRegistry)
                .lowCardinalityKeyValue("endpoint", "/customers")
                .observe(() -> customerCreateTimer.record(() -> {
                    CustomerDto result = service.create(request);
                    customerCreatedCounter.increment();
                    return result;
                }));
    }

    @GetMapping("/recent")
    public List<CustomerDto> getRecent() {
        return Observation.createNotStarted("customer.recent", observationRegistry)
                .lowCardinalityKeyValue("endpoint", "/customers/recent")
                .observe(recentCustomerBuffer::getRecent);
    }

    @GetMapping("/aggregate")
    public AggregationService.AggregatedResponse aggregate() {
        return Observation.createNotStarted("customer.aggregate", observationRegistry)
                .lowCardinalityKeyValue("endpoint", "/customers/aggregate")
                .observe(() -> customerAggregateTimer.record(aggregationService::aggregate));
    }

    // Spring AI — generate a professional bio via local Ollama LLM
    @GetMapping("/{id}/bio")
    public java.util.Map<String, String> generateBio(@PathVariable Long id) {
        CustomerDto customer = service.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Customer not found: " + id));
        return java.util.Map.of("bio", bioService.generateBio(customer));
    }

    // HTTP Interface + Resilience4j — circuit breaker + retry via TodoService
    @GetMapping("/{id}/todos")
    public List<TodoItem> getTodos(@PathVariable Long id) {
        service.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Customer not found: " + id));
        return todoService.getTodos(id);
    }

    // Pattern 2 — synchronous Kafka request-reply
    @GetMapping("/{id}/enrich")
    public EnrichedCustomerDto enrich(@PathVariable Long id) {
        return Observation.createNotStarted("customer.enrich", observationRegistry)
                .lowCardinalityKeyValue("endpoint", "/customers/{id}/enrich")
                .observe(() -> customerEnrichTimer.record(() -> {
                    CustomerDto customer = service.findById(id)
                            .orElseThrow(() -> new NoSuchElementException("Customer not found: " + id));

                    var record = new ProducerRecord<>(customerRequestTopic,
                            String.valueOf(id),
                            new CustomerEnrichRequest(customer.id(), customer.name(), customer.email()));

                    try {
                        CustomerEnrichReply reply = replyingKafkaTemplate
                                .sendAndReceive(record, java.time.Duration.ofSeconds(enrichTimeoutSeconds))
                                .get()
                                .value();
                        log.info("kafka_enrich_reply id={} displayName={}", id, reply.displayName());
                        return new EnrichedCustomerDto(reply.id(), reply.name(), reply.email(), reply.displayName());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted waiting for enrich reply", e);
                    } catch (ExecutionException e) {
                        // KafkaReplyTimeoutException (extends KafkaException, not TimeoutException)
                        if (e.getCause() instanceof KafkaReplyTimeoutException) {
                            throw new IllegalStateException("kafka-timeout",
                                    new java.util.concurrent.TimeoutException("Kafka reply timed out for id=" + id));
                        }
                        throw new IllegalStateException("Enrich failed for id=" + id, e.getCause());
                    }
                }));
    }
}
