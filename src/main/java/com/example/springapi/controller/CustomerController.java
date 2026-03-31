package com.example.springapi.controller;

import com.example.springapi.dto.CreateCustomerRequest;
import com.example.springapi.dto.CustomerDto;
import com.example.springapi.service.AggregationService;
import com.example.springapi.service.CustomerService;
import com.example.springapi.service.RecentCustomerBuffer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/customers")
public class CustomerController {

    private final CustomerService service;
    private final RecentCustomerBuffer recentCustomerBuffer;
    private final AggregationService aggregationService;
    private final ObservationRegistry observationRegistry;
    private final Counter customerCreatedCounter;
    private final Timer customerCreateTimer;
    private final Timer customerFindAllTimer;
    private final Timer customerAggregateTimer;

    public CustomerController(CustomerService service,
                              RecentCustomerBuffer recentCustomerBuffer,
                              AggregationService aggregationService,
                              ObservationRegistry observationRegistry,
                              MeterRegistry meterRegistry) {
        this.service = service;
        this.recentCustomerBuffer = recentCustomerBuffer;
        this.aggregationService = aggregationService;
        this.observationRegistry = observationRegistry;
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
    }

    @GetMapping
    public List<CustomerDto> getAll() {
        return Observation.createNotStarted("customer.find-all", observationRegistry)
                .lowCardinalityKeyValue("endpoint", "/customers")
                .observe(() -> customerFindAllTimer.record(service::findAll));
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
        long start = System.nanoTime();
        try {
            return Observation.createNotStarted("customer.aggregate", observationRegistry)
                    .lowCardinalityKeyValue("endpoint", "/customers/aggregate")
                    .observe(aggregationService::aggregate);
        } finally {
            long duration = System.nanoTime() - start;
            customerAggregateTimer.record(duration, TimeUnit.NANOSECONDS);
        }
    }
}
