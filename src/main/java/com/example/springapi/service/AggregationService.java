package com.example.springapi.service;

import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

@Service
public class AggregationService {

    public AggregatedResponse aggregate() {
        // aggregate with parallel virtual threads
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var customerFuture = executor.submit(this::loadCustomerData);
            var statsFuture = executor.submit(this::loadStats);

            return new AggregatedResponse(
                    customerFuture.get(),
                    statsFuture.get()
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interruption", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Aggregation failed", e);
        }
    }

    private String loadCustomerData() throws InterruptedException {
        Thread.sleep(200);
        return "customer-data";
    }

    private String loadStats() throws InterruptedException {
        Thread.sleep(200);
        return "stats";
    }

    public record AggregatedResponse(String customerData, String stats) {
    }
}
