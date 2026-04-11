package com.example.springapi.integration;

import com.example.springapi.customer.CustomerDto;
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
     * @param customer the customer whose name and email are passed to the prompt
     * @return the raw text content from the model response
     */
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
}
