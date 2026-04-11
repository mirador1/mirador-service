package com.example.springapi.config;

import com.example.springapi.dto.CustomerDto;
import com.example.springapi.service.BioService;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces BioService with a Mockito stub in tests — no Ollama/LLM needed.
 */
@TestConfiguration
public class TestAiConfig {

    @Bean
    @Primary
    BioService bioService() {
        BioService stub = Mockito.mock(BioService.class);
        Mockito.when(stub.generateBio(Mockito.any(CustomerDto.class)))
                .thenReturn("Stub bio for testing");
        return stub;
    }
}
