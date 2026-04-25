package com.mirador.customer;

// SB3 overlay — Jackson V2 (com.fasterxml.jackson.*) instead of V3 (tools.jackson.*).
// See main src/test/java/com/mirador/customer/RecentCustomerBufferTest.java for
// the full test docs ; this overlay only swaps Jackson imports + exception types.
// Created 2026-04-25 wave 7 alongside the main RecentCustomerBuffer SB3 overlay.
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RecentCustomerBuffer} — SB3 overlay variant using
 * Jackson V2 ObjectMapper. Behaviour identical to the main test file.
 */
@ExtendWith(MockitoExtension.class)
class RecentCustomerBufferTest {

    @Mock
    StringRedisTemplate redisTemplate;

    @Mock
    ListOperations<String, String> listOps;

    RecentCustomerBuffer buffer;
    ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForList()).thenReturn(listOps);
        buffer = new RecentCustomerBuffer(redisTemplate, objectMapper);
    }

    private static CustomerDto dto(long id) {
        return new CustomerDto(id, "Name" + id, "user" + id + "@example.com");
    }

    @Test
    void add_callsLeftPushThenTrim_inOrder() throws Exception {
        CustomerDto d = dto(1);
        String expectedJson = objectMapper.writeValueAsString(d);

        buffer.add(d);

        // LPUSH then LTRIM — order is critical; swapping them would lose the new entry
        InOrder order = inOrder(listOps);
        order.verify(listOps).leftPush(RecentCustomerBuffer.KEY, expectedJson);
        order.verify(listOps).trim(RecentCustomerBuffer.KEY, 0L, 9L);
    }

    @Test
    void getRecent_deserializesJsonEntries() throws Exception {
        CustomerDto d1 = dto(1);
        CustomerDto d2 = dto(2);
        List<String> jsons = List.of(
                objectMapper.writeValueAsString(d1),
                objectMapper.writeValueAsString(d2));
        when(listOps.range(RecentCustomerBuffer.KEY, 0L, 9L)).thenReturn(jsons);

        List<CustomerDto> result = buffer.getRecent();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo(1);
        assertThat(result.get(1).id()).isEqualTo(2);
    }

    @Test
    void getRecent_filtersOutMalformedEntries() {
        when(listOps.range(anyString(), anyLong(), anyLong()))
                .thenReturn(List.of("not-valid-json", "{\"id\":2,\"name\":\"Bob\",\"email\":\"b@x.com\"}"));

        List<CustomerDto> result = buffer.getRecent();

        // Malformed JSON is silently dropped; valid entry is returned
        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(2);
    }

    @Test
    void getRecent_returnsEmptyList_whenRedisReturnsNull() {
        when(listOps.range(anyString(), anyLong(), anyLong())).thenReturn(null);

        assertThat(buffer.getRecent()).isEmpty();
    }

    @Test
    void size_delegatesToLlen() {
        when(listOps.size(RecentCustomerBuffer.KEY)).thenReturn(7L);

        assertThat(buffer.size()).isEqualTo(7L);
        verify(listOps).size(RecentCustomerBuffer.KEY);
    }

    @Test
    void size_returnsZero_whenRedisReturnsNull() {
        when(listOps.size(anyString())).thenReturn(null);

        assertThat(buffer.size()).isZero();
    }
}
