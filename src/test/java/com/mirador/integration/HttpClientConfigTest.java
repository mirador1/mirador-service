package com.mirador.integration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link HttpClientConfig#jsonPlaceholderClient()} — pin
 * the HTTP Interface proxy wiring.
 *
 * <p>Pinned contract: the bean returns a {@link JsonPlaceholderClient}
 * proxy backed by {@link org.springframework.web.client.RestClient} via
 * {@link org.springframework.web.client.support.RestClientAdapter}.
 * Construction is at startup (not on first call), so this test
 * verifies the factory itself doesn't throw — production-side a NPE
 * here would prevent application bootstrap.
 */
class HttpClientConfigTest {

    @Test
    void jsonPlaceholderClient_returnsNonNullProxyAtConstructionTime() {
        // Pinned: the @Bean factory is called eagerly at Spring context
        // refresh — any exception here would prevent the whole app from
        // starting. The proxy itself is lazy (no HTTP calls until first
        // method invoke).
        HttpClientConfig config = new HttpClientConfig();

        JsonPlaceholderClient client = config.jsonPlaceholderClient();

        assertThat(client).isNotNull();
    }

    @Test
    void jsonPlaceholderClient_returnsProxyImplementingTheInterface() {
        // Pinned: the returned proxy MUST implement JsonPlaceholderClient
        // (not just Object). Spring's HttpServiceProxyFactory generates
        // a proxy class implementing the target interface — verifying
        // the type catches a regression where the factory configuration
        // produced something else.
        HttpClientConfig config = new HttpClientConfig();

        JsonPlaceholderClient client = config.jsonPlaceholderClient();

        // The runtime type is a proxy class, not JsonPlaceholderClient
        // itself ; we verify it's-a JsonPlaceholderClient.
        assertThat(client).isInstanceOf(JsonPlaceholderClient.class);
    }
}
