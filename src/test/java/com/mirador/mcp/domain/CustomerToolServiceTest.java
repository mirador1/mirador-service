package com.mirador.mcp.domain;

import com.mirador.customer.Customer;
import com.mirador.customer.CustomerRepository;
import com.mirador.mcp.dto.Customer360Dto;
import com.mirador.order.Order;
import com.mirador.order.OrderRepository;
import com.mirador.order.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CustomerToolService} — focus on the aggregate
 * arithmetic (count, sum, last-order timestamp) and the not-found path.
 */
class CustomerToolServiceTest {

    private CustomerRepository customers;
    private OrderRepository orders;
    private CustomerToolService service;

    @BeforeEach
    void setUp() {
        customers = mock(CustomerRepository.class);
        orders = mock(OrderRepository.class);
        service = new CustomerToolService(customers, orders);
    }

    @Test
    void aggregatesCountSumAndLastOrderAcrossOrders() {
        Customer c = customer(7L, "Alice", "a@x.com");
        when(customers.findById(7L)).thenReturn(Optional.of(c));

        Instant t1 = Instant.parse("2026-01-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-01-02T10:00:00Z");
        // Repository returns DESC by createdAt so t2 first.
        when(orders.findByCustomerIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(
                order(1L, new BigDecimal("10.00"), t2),
                order(2L, new BigDecimal("5.50"), t1)
        ));

        Object result = service.getCustomer360(7L);
        assertThat(result).isInstanceOf(Customer360Dto.class);
        Customer360Dto dto = (Customer360Dto) result;
        assertThat(dto.id()).isEqualTo(7L);
        assertThat(dto.name()).isEqualTo("Alice");
        assertThat(dto.orderCount()).isEqualTo(2L);
        assertThat(dto.totalRevenue()).isEqualByComparingTo(new BigDecimal("15.50"));
        assertThat(dto.lastOrderAt()).isEqualTo(t2);
    }

    @Test
    void zeroOrdersGivesZeroAggregates() {
        Customer c = customer(8L, "Bob", "b@x.com");
        when(customers.findById(8L)).thenReturn(Optional.of(c));
        when(orders.findByCustomerIdOrderByCreatedAtDesc(8L)).thenReturn(List.of());

        Customer360Dto dto = (Customer360Dto) service.getCustomer360(8L);
        assertThat(dto.orderCount()).isZero();
        assertThat(dto.totalRevenue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(dto.lastOrderAt()).isNull();
    }

    @Test
    void notFoundSentinelWhenCustomerAbsent() {
        when(customers.findById(99L)).thenReturn(Optional.empty());
        Object result = service.getCustomer360(99L);
        assertThat(result).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> sentinel = (Map<String, Object>) result;
        assertThat(sentinel.get("status")).isEqualTo("not_found");
    }

    @Test
    void nullTotalAmountHandledWithoutNpe() {
        Customer c = customer(10L, "Carol", "c@x.com");
        when(customers.findById(10L)).thenReturn(Optional.of(c));
        when(orders.findByCustomerIdOrderByCreatedAtDesc(10L)).thenReturn(List.of(
                order(1L, null, Instant.now()),
                order(2L, new BigDecimal("3.00"), Instant.now())
        ));

        Customer360Dto dto = (Customer360Dto) service.getCustomer360(10L);
        assertThat(dto.totalRevenue()).isEqualByComparingTo(new BigDecimal("3.00"));
    }

    private Customer customer(Long id, String name, String email) {
        Customer c = new Customer();
        c.setId(id);
        c.setName(name);
        c.setEmail(email);
        c.setCreatedAt(Instant.now().minusSeconds(3600));
        return c;
    }

    private Order order(Long id, BigDecimal total, Instant createdAt) {
        Order o = new Order();
        o.setId(id);
        o.setStatus(OrderStatus.PENDING);
        o.setTotalAmount(total);
        o.setCreatedAt(createdAt);
        o.setUpdatedAt(createdAt);
        return o;
    }
}
