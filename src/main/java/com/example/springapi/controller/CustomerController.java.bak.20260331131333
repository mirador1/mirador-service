package com.example.springapi.controller;

import com.example.springapi.context.RequestContext;
import com.example.springapi.dto.CreateCustomerRequest;
import com.example.springapi.dto.CustomerDto;
import com.example.springapi.service.AggregationService;
import com.example.springapi.service.CustomerService;
import com.example.springapi.service.RecentCustomerBuffer;
import com.example.springapi.service.TraceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/customers")
public class CustomerController {

    private final CustomerService service;
    private final RecentCustomerBuffer recentCustomerBuffer;
    private final AggregationService aggregationService;
    private final TraceService traceService;

    public CustomerController(
            CustomerService service,
            RecentCustomerBuffer recentCustomerBuffer,
            AggregationService aggregationService,
            TraceService traceService
    ) {
        this.service = service;
        this.recentCustomerBuffer = recentCustomerBuffer;
        this.aggregationService = aggregationService;
        this.traceService = traceService;
    }

    @GetMapping
    public List<CustomerDto> getAll() {
        return service.findAll();
    }

    @PostMapping
    public CustomerDto create(@Valid @RequestBody CreateCustomerRequest request) {
        return service.create(request);
    }

    @GetMapping("/recent")
    public List<CustomerDto> getRecent() {
        return recentCustomerBuffer.getRecent();
    }

    @GetMapping("/aggregate")
    public AggregationService.AggregatedResponse aggregate() {
        return aggregationService.aggregate();
    }

    @GetMapping("/trace-demo")
    public String traceDemo(@RequestHeader(name = "X-Request-Id", required = false) String requestId) throws Exception {
        String effectiveRequestId = (requestId == null || requestId.isBlank()) ? "req-local-demo" : requestId;
        return ScopedValue.where(RequestContext.REQUEST_ID, effectiveRequestId)
                .call(traceService::currentRequestIdOrDefault);
    }
}
