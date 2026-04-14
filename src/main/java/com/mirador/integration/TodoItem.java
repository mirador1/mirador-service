package com.mirador.integration;

/**
 * Immutable DTO representing a todo item from the JSONPlaceholder API.
 *
 * <p>Deserialized from {@code https://jsonplaceholder.typicode.com/users/{id}/todos} JSON:
 * <pre>
 * { "userId": 1, "id": 1, "title": "delectus aut autem", "completed": false }
 * </pre>
 *
 * <p>Java records (JEP 395) are ideal for API response DTOs: the Jackson deserializer
 * maps JSON fields to record components by name, and the record is immutable by design,
 * preventing accidental mutation after deserialization.
 */
public record TodoItem(long userId, long id, String title, boolean completed) {
}
