package com.mirador.mcp.domain;

import com.mirador.product.Product;
import com.mirador.product.ProductDto;
import com.mirador.product.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProductToolService} — covers the threshold default,
 * the sort-by-stock-ascending invariant, and the result cap.
 */
class ProductToolServiceTest {

    private ProductRepository repo;
    private ProductToolService service;

    @BeforeEach
    void setUp() {
        repo = mock(ProductRepository.class);
        service = new ProductToolService(repo);
    }

    @Test
    void defaultThresholdAppliesWhenZero() {
        when(repo.findAll()).thenReturn(List.of(
                product(1L, 5),
                product(2L, 9),
                product(3L, 11), // above default 10 — excluded
                product(4L, 100) // also excluded
        ));

        List<ProductDto> low = service.findLowStockProducts(0);
        assertThat(low).hasSize(2);
        assertThat(low).extracting(ProductDto::stockQuantity).containsExactly(5, 9);
    }

    @Test
    void customThresholdFiltersStrictlyBelow() {
        when(repo.findAll()).thenReturn(List.of(
                product(1L, 4),
                product(2L, 5), // strictly less than 5 → excluded
                product(3L, 6)
        ));

        List<ProductDto> low = service.findLowStockProducts(5);
        assertThat(low).hasSize(1);
        assertThat(low.get(0).stockQuantity()).isEqualTo(4);
    }

    @Test
    void resultIsSortedByStockAscending() {
        when(repo.findAll()).thenReturn(List.of(
                product(1L, 9),
                product(2L, 1),
                product(3L, 5)
        ));

        List<ProductDto> low = service.findLowStockProducts(10);
        assertThat(low).extracting(ProductDto::stockQuantity).containsExactly(1, 5, 9);
    }

    @Test
    void nullStockExcluded() {
        Product p = new Product();
        p.setId(7L);
        p.setName("ghost");
        p.setUnitPrice(new BigDecimal("1"));
        p.setStockQuantity(null);
        when(repo.findAll()).thenReturn(List.of(p));

        List<ProductDto> low = service.findLowStockProducts(10);
        assertThat(low).isEmpty();
    }

    @Test
    void resultCappedAtMaxResults() {
        // 200 low-stock products in a row — service must cap at 100.
        Product[] all = new Product[200];
        for (int i = 0; i < 200; i++) {
            all[i] = product((long) i, 1);
        }
        when(repo.findAll()).thenReturn(List.of(all));

        List<ProductDto> low = service.findLowStockProducts(10);
        assertThat(low).hasSize(ProductToolService.MAX_RESULTS);
    }

    private Product product(Long id, int stock) {
        Product p = new Product();
        p.setId(id);
        p.setName("p-" + id);
        p.setDescription("desc");
        p.setUnitPrice(new BigDecimal("9.99"));
        p.setStockQuantity(stock);
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        return p;
    }
}
