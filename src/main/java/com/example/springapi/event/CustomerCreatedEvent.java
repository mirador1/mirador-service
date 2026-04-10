package com.example.springapi.event;

public record CustomerCreatedEvent(Long id, String name, String email) {
}
