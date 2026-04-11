package com.example.springapi.config;

import com.example.springapi.client.JsonPlaceholderClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * Wires the Spring 6 HTTP Interface proxy for the JSONPlaceholder external API.
 *
 * <h3>HTTP Interface pattern (Spring 6+)</h3>
 * <p>Spring 6 introduced declarative HTTP clients in the style of Spring Data repositories:
 * annotate an interface with {@code @HttpExchange} + {@code @GetExchange}/{@code @PostExchange},
 * then ask Spring to create a runtime proxy via {@link HttpServiceProxyFactory}.
 *
 * <p>This replaces boilerplate like:
 * <pre>
 * restClient.get().uri("...").retrieve().body(List.class)
 * </pre>
 * with a clean, testable interface declaration.
 *
 * <h3>Integration with Resilience4j</h3>
 * <p>The raw client ({@link com.example.springapi.client.JsonPlaceholderClient}) is injected
 * into {@link com.example.springapi.service.TodoService}, which wraps calls with
 * {@code @CircuitBreaker} + {@code @Retry} annotations. The resiliency logic lives in the
 * service layer, not in the HTTP client config, keeping concerns separate.
 *
 * <h3>Observability</h3>
 * <p>When using {@code RestClient} with the Micrometer observation integration (enabled by
 * {@code spring-boot-starter-opentelemetry}), outbound HTTP calls automatically create
 * child spans in the current trace, so JSONPlaceholder calls appear as nested spans in Tempo.
 */
@Configuration
public class HttpClientConfig {

    /**
     * Creates a {@link com.example.springapi.client.JsonPlaceholderClient} proxy backed by
     * a default {@link RestClient}.
     * The {@link RestClientAdapter} bridges Spring's declarative HTTP interface model to the
     * underlying {@link RestClient} implementation.
     */
    @Bean
    JsonPlaceholderClient jsonPlaceholderClient() {
        RestClient restClient = RestClient.builder().build();
        HttpServiceProxyFactory factory = HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build();
        return factory.createClient(JsonPlaceholderClient.class);
    }
}
