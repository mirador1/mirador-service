package com.mirador.product;

import com.mirador.order.OrderDto;
import com.mirador.order.OrderRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for product management.
 *
 * <p>Foundation MR (2026-04-26) : list + get + create + delete. Update
 * (PUT) deferred to a follow-up MR with optimistic locking + audit.
 *
 * <p>No Kafka, no observability metrics, no AI-generation here — keep
 * this controller focused on the domain CRUD. Cross-cutting concerns
 * are added in incremental follow-ups (matches the
 * {@link com.mirador.customer.CustomerController} evolution pattern).
 */
@Tag(name = "Products", description = "Product catalogue : list, get, create, delete")
@RestController
@RequestMapping(ProductController.PATH_PRODUCTS)
public class ProductController {

    static final String PATH_PRODUCTS = "/products";

    private final ProductRepository repo;
    private final OrderRepository orderRepo;

    public ProductController(ProductRepository repo, OrderRepository orderRepo) {
        this.repo = repo;
        this.orderRepo = orderRepo;
    }

    /**
     * List products, optionally filtered by a case-insensitive substring
     * search on name + description. Mirrors the Customer search pattern :
     * the UI debounces 300 ms before issuing the request, so this hot
     * path stays cheap on the backend.
     *
     * <p>An empty / blank {@code search} param falls through to the
     * unfiltered listing — same shape as before the search support
     * landed, so existing callers keep working unchanged.
     */
    @Operation(summary = "List products (paginated, optional search)")
    @GetMapping
    public Page<ProductDto> list(
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20) Pageable pageable) {
        if (search == null || search.isBlank()) {
            return repo.findAll(pageable).map(ProductDto::from);
        }
        return repo.search(search.trim(), pageable).map(ProductDto::from);
    }

    @Operation(summary = "Get product by ID")
    @GetMapping("/{id}")
    public ResponseEntity<ProductDto> get(@PathVariable Long id) {
        return repo.findById(id)
                .map(ProductDto::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "Create a product")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductDto create(@Valid @RequestBody CreateProductRequest req) {
        Product p = new Product();
        p.setName(req.name());
        p.setDescription(req.description());
        p.setUnitPrice(req.unitPrice());
        p.setStockQuantity(req.stockQuantity());
        Product saved = repo.save(p);
        return ProductDto.from(saved);
    }

    /**
     * Update a product. Per shared ADR-0059, modifying {@code unitPrice}
     * here MUST NOT propagate to existing {@code OrderLine.unitPriceAtOrder}
     * (which carries an immutable snapshot). Stock_quantity may go up or
     * down freely (subject to the {@code >= 0} CHECK constraint).
     *
     * <p>Returns 404 if the product doesn't exist (no implicit upsert).
     */
    @Operation(summary = "Update a product (partial body — fields supplied are replaced)")
    @PutMapping("/{id}")
    public ResponseEntity<ProductDto> update(
            @PathVariable Long id,
            @Valid @RequestBody CreateProductRequest req) {
        return repo.findById(id)
                .map(p -> {
                    p.setName(req.name());
                    p.setDescription(req.description());
                    p.setUnitPrice(req.unitPrice());
                    p.setStockQuantity(req.stockQuantity());
                    return ProductDto.from(repo.save(p));
                })
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "Delete a product")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        repo.deleteById(id);
    }

    /**
     * Paginated list of orders that contain at least one line for this
     * product. Replaces the UI-side fan-out previously implemented as
     * "list 50 recent orders + filter client-side" in
     * {@code ProductDetailComponent#findConsumerOrders} on the consumer
     * side.
     *
     * <p>Returns 404 if the product itself doesn't exist (avoids
     * silently returning empty results for a typo in the path) ; an
     * empty page is the legitimate result for an existing but unsold
     * product.
     */
    @Operation(summary = "List orders that contain at least one line for this product")
    @GetMapping("/{id}/orders")
    public ResponseEntity<Page<OrderDto>> ordersForProduct(
            @PathVariable Long id,
            @PageableDefault(size = 20) Pageable pageable) {
        if (!repo.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(orderRepo.findByProductId(id, pageable).map(OrderDto::from));
    }
}
