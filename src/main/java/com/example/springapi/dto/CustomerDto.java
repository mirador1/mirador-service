package com.example.springapi.dto;

/**
 * Read-only DTO used to expose customer data through the REST API.
 *
 * <p>This DTO decouples the API contract from the JPA entity ({@link com.example.springapi.model.Customer}).
 * Benefits:
 * <ul>
 *   <li>The entity can change (add fields, rename columns) without breaking the API consumers.</li>
 *   <li>Sensitive or internal fields (e.g., a future {@code passwordHash}) are never accidentally
 *       serialized to the response body.</li>
 * </ul>
 *
 * <p>Jackson serializes records to JSON using the component names as field names:
 * {@code {"id":1,"name":"Alice","email":"alice@example.com"}}.
 */
public record CustomerDto(Long id, String name, String email) {
}
