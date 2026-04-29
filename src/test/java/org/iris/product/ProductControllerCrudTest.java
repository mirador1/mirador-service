package org.iris.product;

import org.iris.order.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the CRUD paths on {@link ProductController} —
 * complements {@link ProductControllerTest} (search-vs-list dispatch
 * + nested {@code /products/{id}/orders}) and {@link ProductHttpITest}
 * (slow end-to-end with Testcontainers).
 *
 * <p>Pure Mockito ; no Spring context. Closes the JaCoCo gap on the
 * {@code get / create / update / delete} branches.
 */
class ProductControllerCrudTest {

    private ProductRepository repo;
    private ProductController controller;

    @BeforeEach
    void setUp() {
        repo = mock(ProductRepository.class);
        OrderRepository orderRepo = mock(OrderRepository.class);
        controller = new ProductController(repo, orderRepo);
    }

    // ─── GET /products/{id} ──────────────────────────────────────────────────

    @Test
    void get_returnsDtoWhenPresent() {
        Product existing = product(7L, "Laptop", "Premium ultrabook", "1299.99", 25);
        when(repo.findById(7L)).thenReturn(Optional.of(existing));

        ResponseEntity<ProductDto> response = controller.get(7L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isEqualTo(7L);
        assertThat(response.getBody().name()).isEqualTo("Laptop");
        assertThat(response.getBody().unitPrice()).isEqualByComparingTo("1299.99");
        assertThat(response.getBody().stockQuantity()).isEqualTo(25);
    }

    @Test
    void get_returns404WhenAbsent() {
        when(repo.findById(99L)).thenReturn(Optional.empty());

        ResponseEntity<ProductDto> response = controller.get(99L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    // ─── POST /products ──────────────────────────────────────────────────────

    @Test
    void create_returnsDtoFromSavedEntity() {
        when(repo.save(any(Product.class))).thenAnswer(inv -> {
            Product p = inv.getArgument(0);
            p.setId(123L);
            p.setCreatedAt(Instant.now());
            return p;
        });

        ProductDto dto = controller.create(new CreateProductRequest(
                "Headphones", "Noise-cancelling", new BigDecimal("249.00"), 50));

        assertThat(dto.id()).isEqualTo(123L);
        assertThat(dto.name()).isEqualTo("Headphones");
        assertThat(dto.description()).isEqualTo("Noise-cancelling");
        assertThat(dto.unitPrice()).isEqualByComparingTo("249.00");
        assertThat(dto.stockQuantity()).isEqualTo(50);
        verify(repo).save(any(Product.class));
    }

    @Test
    void create_acceptsNullDescription() {
        // @Size(max=10_000) on description allows null (only applies if
        // present). Pinned : the controller passes it through unchanged
        // — the DB column is nullable.
        when(repo.save(any(Product.class))).thenAnswer(inv -> {
            Product p = inv.getArgument(0);
            p.setId(124L);
            return p;
        });

        ProductDto dto = controller.create(new CreateProductRequest(
                "Keyboard", null, new BigDecimal("89.00"), 30));

        assertThat(dto.description()).isNull();
    }

    // ─── PUT /products/{id} ──────────────────────────────────────────────────

    @Test
    void update_replacesAllFieldsAndReturns200() {
        // The PUT signature is "full replacement" : every field on the
        // body lands on the entity (no patch semantics). Pinned because a
        // future refactor to PATCH would silently break consumers if
        // we don't lock down full-replace here.
        Product existing = product(7L, "Old", "Old desc", "9.99", 5);
        when(repo.findById(7L)).thenReturn(Optional.of(existing));
        when(repo.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<ProductDto> response = controller.update(7L,
                new CreateProductRequest("New", "New desc", new BigDecimal("19.99"), 10));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ProductDto dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.name()).isEqualTo("New");
        assertThat(dto.description()).isEqualTo("New desc");
        assertThat(dto.unitPrice()).isEqualByComparingTo("19.99");
        assertThat(dto.stockQuantity()).isEqualTo(10);
        verify(repo).save(any(Product.class));
    }

    @Test
    void update_returns404_whenProductMissing_andDoesNotSave() {
        // Pinned : no implicit upsert. PUT on a missing id MUST 404 ;
        // a request that lands when no product yet exists shouldn't
        // create it silently.
        when(repo.findById(99L)).thenReturn(Optional.empty());

        ResponseEntity<ProductDto> response = controller.update(99L,
                new CreateProductRequest("X", null, BigDecimal.ONE, 1));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(repo, never()).save(any(Product.class));
    }

    // ─── DELETE /products/{id} ───────────────────────────────────────────────

    @Test
    void delete_callsRepoDeleteById() {
        controller.delete(5L);
        verify(repo).deleteById(5L);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static Product product(Long id, String name, String description, String price, int stock) {
        Product p = new Product();
        p.setId(id);
        p.setName(name);
        p.setDescription(description);
        p.setUnitPrice(new BigDecimal(price));
        p.setStockQuantity(stock);
        p.setCreatedAt(Instant.parse("2026-04-27T12:00:00Z"));
        p.setUpdatedAt(Instant.parse("2026-04-27T12:00:00Z"));
        return p;
    }
}
