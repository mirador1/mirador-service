package com.mirador.product;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    public ProductController(ProductRepository repo) {
        this.repo = repo;
    }

    @Operation(summary = "List products (paginated)")
    @GetMapping
    public Page<ProductDto> list(@PageableDefault(size = 20) Pageable pageable) {
        return repo.findAll(pageable).map(ProductDto::from);
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

    @Operation(summary = "Delete a product")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        repo.deleteById(id);
    }
}
