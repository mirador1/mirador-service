package com.mirador.customer;

// [Spring Boot 4 / Jackson 3] — Jackson 3.x moved to the tools.jackson package.
// ObjectMapper is still the central API; exceptions are now unchecked (JacksonException extends RuntimeException).
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Redis-backed ring buffer holding the 10 most recently created customers.
 *
 * <p>Replaces the previous in-memory {@code LinkedList} implementation with a Redis
 * {@code LIST} data structure using three atomic operations:
 * <ol>
 *   <li>{@code LPUSH key json} — prepend the new customer JSON to the front of the list.</li>
 *   <li>{@code LTRIM key 0 9} — trim the list to the 10 most recent entries in a single round-trip.</li>
 *   <li>{@code LRANGE key 0 9} — read all entries on each GET request.</li>
 * </ol>
 *
 * <h3>Why Redis over in-memory?</h3>
 * <ul>
 *   <li><b>Survives pod restarts</b> — in-process caches lose state on redeploy; Redis persists.</li>
 *   <li><b>Shared across replicas</b> — all application instances share the same view
 *       of recent customers when running in Kubernetes with multiple pods.</li>
 *   <li><b>No synchronization needed</b> — Redis is single-threaded; LPUSH + LTRIM are
 *       individually atomic, so there is no data race.</li>
 * </ul>
 *
 * <p>Entries are serialized as JSON strings using Jackson's {@link ObjectMapper}.
 * The key {@value #KEY} is shared across the cluster; use a namespace prefix in
 * multi-tenant deployments.
 *
 * <p>[Spring Boot 3+ / spring-data-redis]
 */
@Service
public class RecentCustomerBuffer {

    private static final Logger log = LoggerFactory.getLogger(RecentCustomerBuffer.class);
    /** Redis list key. All replicas of this application share this key. */
    static final String KEY = "customer-service:recent:customers";
    /** Maximum number of entries to retain in the list. */
    private static final long MAX_SIZE = 10L;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RecentCustomerBuffer(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Prepends {@code dto} to the Redis list and trims to {@value #MAX_SIZE} entries.
     *
     * <p>LPUSH + LTRIM are not atomic together, but the worst-case race is the list
     * briefly exceeding {@value #MAX_SIZE} by one entry between the two commands —
     * acceptable for a non-critical display buffer.
     */
    public void add(CustomerDto dto) {
        try {
            String json = objectMapper.writeValueAsString(dto);
            var ops = redisTemplate.opsForList();
            ops.leftPush(KEY, json);
            ops.trim(KEY, 0, MAX_SIZE - 1);
        } catch (JacksonException e) {
            // Non-critical: log and continue — buffer miss is acceptable
            log.warn("recent_buffer_add_failed id={} cause={}", dto.id(), e.getMessage());
        }
    }

    /**
     * Returns up to {@value #MAX_SIZE} recent customers from Redis.
     * Returns an empty list if Redis is unavailable or contains no entries.
     */
    public List<CustomerDto> getRecent() {
        try {
            List<String> jsons = redisTemplate.opsForList().range(KEY, 0, MAX_SIZE - 1);
            if (jsons == null) return List.of();
            return jsons.stream()
                    .map(this::deserialize)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            log.warn("recent_buffer_read_failed cause={}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Returns the current number of entries in the Redis list.
     * Used by the Micrometer Gauge in {@link com.mirador.observability.ObservabilityConfig}.
     */
    public long size() {
        try {
            Long s = redisTemplate.opsForList().size(KEY);
            return s != null ? s : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private CustomerDto deserialize(String json) {
        try {
            return objectMapper.readValue(json, CustomerDto.class);
        } catch (JacksonException e) {
            log.warn("recent_buffer_deserialize_failed cause={}", e.getMessage());
            return null;
        }
    }
}
