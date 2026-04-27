package com.mirador.product;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProductController} — focused on the search-vs-list
 * dispatch behaviour added 2026-04-27 (server-side search, mirrors the
 * Customer pattern). The CRUD paths (get / create / update / delete) are
 * exercised by integration tests in a follow-up MR ; here we only cover
 * the new branching + edge cases the HTTP layer can produce :
 *
 * <ol>
 *   <li>Null search → unfiltered findAll().</li>
 *   <li>Blank search ({@code ""}, {@code "   "}) → unfiltered findAll() (no
 *       no-op SQL with a {@code %% } wildcard).</li>
 *   <li>Trimmed search → search() with the trimmed value (UI debounces
 *       and may send "  laptop " from a sloppy paste).</li>
 *   <li>Real search → search() with the literal value.</li>
 * </ol>
 */
class ProductControllerTest {

    private ProductRepository repo;
    private ProductController controller;

    @BeforeEach
    void setUp() {
        repo = mock(ProductRepository.class);
        controller = new ProductController(repo);
    }

    @Test
    void list_withNullSearch_callsFindAllNotSearch() {
        when(repo.findAll(any(Pageable.class))).thenReturn(emptyPage());

        Page<ProductDto> result = controller.list(null, Pageable.unpaged());

        assertThat(result).isEmpty();
        verify(repo).findAll(any(Pageable.class));
        verify(repo, never()).search(any(), any());
    }

    @Test
    void list_withBlankSearch_callsFindAllNotSearch() {
        // Blank string is the typical "user cleared the search box" case.
        // Falling through to findAll() avoids issuing SQL like
        // `LIKE '%%'` which would match everything but with a wasted index
        // scan.
        when(repo.findAll(any(Pageable.class))).thenReturn(emptyPage());

        controller.list("", Pageable.unpaged());
        controller.list("   ", Pageable.unpaged());

        verify(repo, times(2)).findAll(any(Pageable.class));
        verify(repo, never()).search(any(), any());
    }

    @Test
    void list_withSearch_callsSearchWithTrimmedValue() {
        // The UI sends "  laptop " from a sloppy paste — repository
        // contract is "search expects a trimmed value", so the controller
        // owns the trim. Repository tests should NOT have to handle
        // leading/trailing whitespace.
        when(repo.search(eq("laptop"), any(Pageable.class))).thenReturn(emptyPage());

        controller.list("  laptop ", Pageable.unpaged());

        verify(repo).search(eq("laptop"), any(Pageable.class));
        verify(repo, never()).findAll(any(Pageable.class));
    }

    @Test
    void list_returnsDtoPage() {
        Product alpha = product(1L, "Alpha", "the first letter");
        Product beta = product(2L, "Beta", "the second letter");
        when(repo.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(alpha, beta)));

        Page<ProductDto> result = controller.list(null, Pageable.unpaged());

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent().get(0).name()).isEqualTo("Alpha");
        assertThat(result.getContent().get(1).name()).isEqualTo("Beta");
    }

    private static Page<Product> emptyPage() {
        return new PageImpl<>(List.of());
    }

    private static Product product(Long id, String name, String description) {
        Product p = new Product();
        p.setId(id);
        p.setName(name);
        p.setDescription(description);
        p.setUnitPrice(new BigDecimal("9.99"));
        p.setStockQuantity(10);
        p.setCreatedAt(Instant.parse("2026-04-27T12:00:00Z"));
        return p;
    }
}
