package com.mirador.mcp.domain;

import com.mirador.order.Order;
import com.mirador.order.OrderDto;
import com.mirador.order.OrderRepository;
import com.mirador.order.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OrderToolService} — covers the limit logic, status
 * filter routing, the not-found sentinel, and the cancellation flow.
 */
class OrderToolServiceTest {

    private OrderRepository repo;
    private OrderToolService service;

    @BeforeEach
    void setUp() {
        repo = mock(OrderRepository.class);
        service = new OrderToolService(repo);
    }

    @Test
    void zeroLimitFallsBackTo20() {
        assertThat(OrderToolService.effectiveLimit(0)).isEqualTo(20);
        assertThat(OrderToolService.effectiveLimit(-5)).isEqualTo(20);
    }

    @Test
    void hugeLimitClippedToMax() {
        assertThat(OrderToolService.effectiveLimit(99_999)).isEqualTo(OrderToolService.MAX_RESULTS);
    }

    @Test
    void listRecentOrdersWithoutStatusUsesFindAll() {
        Order o = order(1L, OrderStatus.PENDING);
        when(repo.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(o)));

        List<OrderDto> result = service.listRecentOrders(10, null);

        verify(repo, times(1)).findAll(any(Pageable.class));
        verify(repo, never()).findByStatus(any(OrderStatus.class), any(Pageable.class));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(1L);
    }

    @Test
    void listRecentOrdersWithStatusUsesFindByStatus() {
        Order o = order(2L, OrderStatus.SHIPPED);
        when(repo.findByStatus(eq(OrderStatus.SHIPPED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(o)));

        List<OrderDto> result = service.listRecentOrders(10, OrderStatus.SHIPPED);

        verify(repo, never()).findAll(any(Pageable.class));
        verify(repo, times(1)).findByStatus(eq(OrderStatus.SHIPPED), any(Pageable.class));
        assertThat(result).hasSize(1);
    }

    @Test
    void getOrderByIdReturnsDtoWhenFound() {
        when(repo.findById(7L)).thenReturn(Optional.of(order(7L, OrderStatus.PENDING)));

        Object result = service.getOrderById(7L);
        assertThat(result).isInstanceOf(OrderDto.class);
        assertThat(((OrderDto) result).id()).isEqualTo(7L);
    }

    @Test
    void getOrderByIdReturnsNotFoundSentinelWhenAbsent() {
        when(repo.findById(99L)).thenReturn(Optional.empty());

        Object result = service.getOrderById(99L);
        assertThat(result).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> sentinel = (Map<String, Object>) result;
        assertThat(sentinel.get("status")).isEqualTo("not_found");
        assertThat(sentinel.get("id")).isEqualTo(99L);
    }

    @Test
    void createOrderPersistsPendingZeroTotal() {
        when(repo.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(123L);
            o.setCreatedAt(Instant.now());
            o.setUpdatedAt(Instant.now());
            return o;
        });

        OrderDto dto = service.createOrder(42L);
        assertThat(dto.id()).isEqualTo(123L);
        assertThat(dto.customerId()).isEqualTo(42L);
        assertThat(dto.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(dto.totalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void cancelOrderTransitionsToCancelled() {
        Order existing = order(5L, OrderStatus.PENDING);
        when(repo.findById(5L)).thenReturn(Optional.of(existing));
        when(repo.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        Object result = service.cancelOrder(5L);
        assertThat(result).isInstanceOf(OrderDto.class);
        assertThat(((OrderDto) result).status()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancelOrderReturnsNotFoundWhenAbsent() {
        when(repo.findById(404L)).thenReturn(Optional.empty());
        Object result = service.cancelOrder(404L);
        assertThat(result).isInstanceOf(Map.class);
    }

    private Order order(Long id, OrderStatus status) {
        Order o = new Order();
        o.setId(id);
        o.setCustomerId(99L);
        o.setStatus(status);
        o.setTotalAmount(new BigDecimal("12.50"));
        o.setCreatedAt(Instant.now().minusSeconds(60));
        o.setUpdatedAt(Instant.now());
        return o;
    }
}
