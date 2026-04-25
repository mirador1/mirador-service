package com.mirador.customer;

// SB3 overlay — Spring Boot 3.x ships Jackson V2 (com.fasterxml.jackson.*).
// The main file uses Jackson V3 (tools.jackson.*) which is the SB4 default.
// In SB3 mode, V3 isn't on the classpath and `tools.jackson.databind.ObjectMapper`
// fails to resolve at runtime — pipeline #2478844997 evidence : RecentCustomerBufferTest
// crashed with NoClassDefFoundError on tools.jackson.databind.ObjectMapper$PrivateBuilder.
//
// This overlay swaps :
//   - import : tools.jackson.* → com.fasterxml.jackson.*
//   - exception : JacksonException → JsonProcessingException (V2 location, checked)
//   - method bodies : identical (writeValueAsString / readValue exist in both)
//
// Created 2026-04-25 wave 7 (svc 1.0.56) per ADR-0060 (SB3 prod-grade) +
// user directive : "ne pas impacter SB4 par des versions SB3, réécrire le
// code impacté". Main code keeps V3 as the canonical default-target API.
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Redis-backed ring buffer holding the 10 most recently created customers.
 *
 * <p>SB3 overlay variant : same semantics as the main file, only the Jackson
 * import roots differ (V2 vs V3).
 *
 * <p>See main {@code src/main/java/com/mirador/customer/RecentCustomerBuffer.java}
 * for the full Javadoc — kept short here to minimise overlay drift.
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
     */
    public void add(CustomerDto dto) {
        try {
            String json = objectMapper.writeValueAsString(dto);
            var ops = redisTemplate.opsForList();
            ops.leftPush(KEY, json);
            ops.trim(KEY, 0, MAX_SIZE - 1);
        } catch (JsonProcessingException e) {
            // Non-critical: log and continue — buffer miss is acceptable.
            log.warn("recent_buffer_add_failed id={} cause={}", dto.id(), e.getMessage());
        }
    }

    /**
     * Returns up to {@value #MAX_SIZE} recent customers from Redis.
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
     */
    public long size() {
        try {
            Long s = redisTemplate.opsForList().size(KEY);
            return s != null ? s : 0L;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private CustomerDto deserialize(String json) {
        try {
            return objectMapper.readValue(json, CustomerDto.class);
        } catch (JsonProcessingException e) {
            log.warn("recent_buffer_deserialize_failed cause={}", e.getMessage());
            return null;
        }
    }
}
