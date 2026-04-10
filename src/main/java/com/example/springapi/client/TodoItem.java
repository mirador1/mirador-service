package com.example.springapi.client;

public record TodoItem(long userId, long id, String title, boolean completed) {
}
