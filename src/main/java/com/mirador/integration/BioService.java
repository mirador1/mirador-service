package com.mirador.integration;

import com.mirador.customer.CustomerDto;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * Generates professional customer bios using a local LLM via Spring AI.
 *
 * <p>Spring AI's {@code ChatClient} provides a portable, vendor-agnostic abstraction over
 * language model providers. In this project the backend is Ollama (a local LLM runtime),
 * configured via {@code spring.ai.ollama.*} in {@code application.yml}. Switching to OpenAI,
 * Anthropic, or another provider requires only a dependency and configuration change — no
 * code change in this class.
 *
 * <p>The {@code defaultSystem()} prompt establishes the model's persona for all calls made
 * through this {@code ChatClient} instance. The user prompt uses named parameters
 * ({@code {name}}, {@code {email}}) which Spring AI resolves via {@code .param()} — this
 * avoids string concatenation and keeps the prompt template readable.
 *
 * <p>The call is synchronous and blocking. On a standard laptop with Ollama + a 7B model,
 * typical latency is 1–5 seconds depending on hardware. For production use, consider making
 * this non-blocking (reactive or virtual-thread executor) and adding a Resilience4j timeout.
 */
@Service
public class BioService {

    private static final Logger log = LoggerFactory.getLogger(BioService.class);
    // Resilience4j instance name — must match keys in application.yml under resilience4j.*
    private static final String CIRCUIT_BREAKER_NAME = "ollama";
    // Bulkhead name — must match resilience4j.bulkhead.instances.ollama in application.yml
    private static final String BULKHEAD_NAME = "ollama";

    private final ChatClient chatClient;

    /**
     * Builds a {@code ChatClient} with a fixed system prompt.
     * Spring AI injects the {@code ChatClient.Builder} (autoconfigured from the Ollama starter)
     * so no manual HTTP client setup is needed.
     */
    public BioService(ChatClient.Builder builder) {
        this.chatClient = builder
                .defaultSystem("You are a concise copywriter. Write short, professional bios.")
                .build();
    }

    /**
     * Calls the LLM to generate a 2-sentence bio for the given customer.
     *
     * <p>Protected by two Resilience4j mechanisms applied in this order:
     * <ol>
     *   <li><b>Semaphore bulkhead</b> — caps concurrent LLM calls to {@code max-concurrent-calls}
     *       (application.yml: {@code resilience4j.bulkhead.instances.ollama}). If all permits are
     *       taken, the call fails immediately (no wait) — preventing a slow Ollama from exhausting
     *       the virtual-thread carrier pool under load.</li>
     *   <li><b>Circuit breaker</b> — opens after the failure-rate threshold is exceeded and
     *       short-circuits subsequent calls to {@link #fallbackBio}, giving Ollama time to recover.</li>
     * </ol>
     *
     * @param customer the customer whose name and email are passed to the LLM prompt
     * @return the raw text from the model, or "Bio temporarily unavailable." if the circuit is open
     */
    @Bulkhead(name = BULKHEAD_NAME, fallbackMethod = "fallbackBio")
    @CircuitBreaker(name = CIRCUIT_BREAKER_NAME, fallbackMethod = "fallbackBio")
    public String generateBio(CustomerDto customer) {
        return chatClient.prompt()
                .user(u -> u.text(
                        "Write a 2-sentence professional bio for {name} ({email}). "
                        + "Be friendly and concise.")
                        .param("name", customer.name())
                        .param("email", customer.email()))
                .call()
                .content();
    }

    /**
     * Fallback invoked when the circuit is open or the Ollama call fails.
     * The method signature must match {@link #generateBio} with an additional {@code Throwable} parameter.
     */
    String fallbackBio(CustomerDto customer, Throwable t) {
        log.warn("bio_fallback name={} cause={}", customer.name(), t.getMessage());
        return "Bio temporarily unavailable.";
    }
}
