package com.mirador.integration;

import com.mirador.customer.CustomerDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for BioService — mocks ChatClient.Builder to avoid a running Ollama instance.
 *
 * Note: @CircuitBreaker and @Bulkhead annotations are NOT active here (no Spring AOP proxy).
 * These tests verify the service logic and fallback behaviour. The resilience patterns
 * are exercised only in full integration tests with a running application context.
 */
class BioServiceTest {

    private ChatClient chatClient;
    private BioService service;

    @BeforeEach
    void setUp() {
        // Mock the builder chain: builder.defaultSystem(...).build() → chatClient
        chatClient = mock(ChatClient.class);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.defaultSystem(any(String.class))).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);
        service = new BioService(builder);
    }

    @Test
    @SuppressWarnings("unchecked")
    void generateBio_callsChatClientAndReturnsContent() {
        ChatClientRequestSpec requestSpec = mock(ChatClientRequestSpec.class);
        CallResponseSpec callResponseSpec = mock(CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        // user(Consumer<PromptUserSpec>) overload — explicit cast avoids ambiguity
        when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn("Alice is a software engineer.");

        var customer = new CustomerDto(1L, "Alice", "alice@example.com");
        String bio = service.generateBio(customer);

        assertThat(bio).isEqualTo("Alice is a software engineer.");
    }

    @Test
    void fallbackBio_returnsUnavailableMessage() {
        // Fallback is called when circuit is open or Ollama times out —
        // must return a user-visible placeholder, not null or an exception.
        var customer = new CustomerDto(2L, "Bob", "bob@example.com");
        String bio = service.fallbackBio(customer, new RuntimeException("connection refused"));

        assertThat(bio).isEqualTo("Bio temporarily unavailable.");
    }

    @Test
    void fallbackBio_doesNotThrowRegardlessOfException() {
        var customer = new CustomerDto(3L, "Carol", "carol@example.com");
        assertThat(service.fallbackBio(customer, new OutOfMemoryError("heap"))).isNotEmpty();
    }
}
