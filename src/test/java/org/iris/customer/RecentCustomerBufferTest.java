package org.iris.customer;

import tools.jackson.databind.ObjectMapper;
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
 * Unit tests for {@link RecentCustomerBuffer} — no Spring context needed.
 *
 * <p>Redis operations are mocked with Mockito so the tests run without a real
 * Redis instance. Integration-level tests (with a real Redis container) are
 * covered by the {@code ITest} suite via {@link org.iris.AbstractIntegrationTest}.
 *
 * <p>Tests verify:
 * <ul>
 *   <li>add() calls LPUSH then LTRIM in order (order matters for correctness).</li>
 *   <li>getRecent() calls LRANGE and deserializes the JSON payloads.</li>
 *   <li>getRecent() filters out malformed JSON entries (null after deserialize).</li>
 *   <li>size() delegates to LLEN via {@code opsForList().size()}.</li>
 * </ul>
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
        // lenient() — one test (add_swallowsJacksonException…) builds its
        // own redisTemplate and never reaches opsForList(), so strict
        // Mockito would flag this stub as unused.
        org.mockito.Mockito.lenient().when(redisTemplate.opsForList()).thenReturn(listOps);
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

    // ─── failure containment paths (Redis down / serialisation breaks) ───────

    @Test
    void add_swallowsJacksonException_doesNotPropagate() {
        // Use a broken ObjectMapper that throws on writeValueAsString. The
        // contract is "non-critical: log + continue". A propagating
        // exception here would kill the customer-create endpoint just
        // because the recent-customers buffer can't serialise — that's
        // unacceptable.
        // Construct a fresh redisTemplate (no stubbing) so Mockito strict
        // mode doesn't flag the @BeforeEach opsForList() stub as unused.
        StringRedisTemplate freshTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
        ObjectMapper brokenMapper = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) {
                throw new TestJacksonException("boom");
            }
        };
        RecentCustomerBuffer buf = new RecentCustomerBuffer(freshTemplate, brokenMapper);

        // Must not throw. The mapper raises before any Redis call.
        buf.add(dto(99));
    }

    /** Test-only subclass to surface JacksonException with a public ctor. */
    static final class TestJacksonException extends tools.jackson.core.JacksonException {
        TestJacksonException(String msg) { super(msg); }
    }

    @Test
    void getRecent_swallowsRedisException_returnsEmptyList() {
        when(listOps.range(anyString(), anyLong(), anyLong()))
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException(
                        "redis down"));

        assertThat(buffer.getRecent()).isEmpty();
    }

    @Test
    void size_swallowsRedisException_returnsZero() {
        when(listOps.size(anyString()))
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException(
                        "redis down"));

        assertThat(buffer.size()).isZero();
    }
}
