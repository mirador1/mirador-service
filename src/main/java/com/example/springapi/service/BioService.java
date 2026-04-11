package com.example.springapi.service;

import com.example.springapi.dto.CustomerDto;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class BioService {

    private final ChatClient chatClient;

    public BioService(ChatClient.Builder builder) {
        this.chatClient = builder
                .defaultSystem("You are a concise copywriter. Write short, professional bios.")
                .build();
    }

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
