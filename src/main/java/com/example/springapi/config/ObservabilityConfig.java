package com.example.springapi.config;

import com.example.springapi.service.RecentCustomerBuffer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObservabilityConfig {

    @Bean
    Gauge recentCustomerBufferGauge(MeterRegistry registry, RecentCustomerBuffer recentCustomerBuffer) {
        return Gauge.builder("customer.recent.buffer.size", recentCustomerBuffer, buffer -> {
                    try {
                        return buffer.getRecent().size();
                    } catch (Exception ex) {
                        return 0;
                    }
                })
                .description("Current size of the recent customer in-memory buffer")
                .register(registry);
    }
}
