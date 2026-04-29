package org.iris.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the CRUD paths on {@link OrderController} —
 * complements {@link OrderControllerTest} (which focuses on
 * {@code PUT /orders/{id}/status} state-machine wiring).
 *
 * <p>Pure Mockito ; no Spring context. Fast (<5ms each). The
 * happy-path of these endpoints is already exercised end-to-end by
 * {@link OrderHttpITest}, but those tests need Testcontainers + a
 * full Spring boot. This unit-level coverage closes the JaCoCo gap
 * without paying the IT startup cost.
 */
class OrderControllerCrudTest {

    private OrderRepository repo;
    private OrderController controller;

    @BeforeEach
    void setUp() {
        repo = mock(OrderRepository.class);
        controller = new OrderController(repo);
    }

    @Test
    void list_returnsDtoPage() {
        Order o1 = order(1L, 42L, OrderStatus.PENDING, BigDecimal.ZERO);
        Order o2 = order(2L, 42L, OrderStatus.SHIPPED, new BigDecimal("19.99"));
        Pageable pageable = PageRequest.of(0, 20);
        when(repo.findAll(pageable)).thenReturn(new PageImpl<>(List.of(o1, o2), pageable, 2));

        Page<OrderDto> result = controller.list(pageable);

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent().get(0).id()).isEqualTo(1L);
        assertThat(result.getContent().get(1).status()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    void get_returnsDtoWhenPresent() {
        Order existing = order(7L, 42L, OrderStatus.CONFIRMED, new BigDecimal("99.00"));
        when(repo.findById(7L)).thenReturn(Optional.of(existing));

        ResponseEntity<OrderDto> response = controller.get(7L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isEqualTo(7L);
        assertThat(response.getBody().status()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void get_returns404WhenAbsent() {
        when(repo.findById(99L)).thenReturn(Optional.empty());

        ResponseEntity<OrderDto> response = controller.get(99L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void create_setsDefaults_andSavesEmptyPendingOrder() {
        // Pinned : POST body carries only customerId — controller MUST
        // default status=PENDING + totalAmount=0 (lines come later via
        // /orders/{id}/lines, totalAmount is recomputed there).
        when(repo.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(123L);
            o.setCreatedAt(Instant.now());
            return o;
        });

        OrderDto dto = controller.create(new CreateOrderRequest(42L));

        assertThat(dto.id()).isEqualTo(123L);
        assertThat(dto.customerId()).isEqualTo(42L);
        assertThat(dto.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(dto.totalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(repo).save(any(Order.class));
    }

    @Test
    void delete_callsRepoDeleteById() {
        controller.delete(5L);
        verify(repo).deleteById(5L);
    }

    private static Order order(Long id, Long customerId, OrderStatus status, BigDecimal total) {
        Order o = new Order();
        o.setId(id);
        o.setCustomerId(customerId);
        o.setStatus(status);
        o.setTotalAmount(total);
        o.setCreatedAt(Instant.parse("2026-04-27T10:00:00Z"));
        o.setUpdatedAt(Instant.parse("2026-04-27T10:00:00Z"));
        return o;
    }
}
