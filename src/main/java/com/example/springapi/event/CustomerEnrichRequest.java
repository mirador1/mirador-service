package com.example.springapi.event;

public record CustomerEnrichRequest(Long id, String name, String email) {
}
