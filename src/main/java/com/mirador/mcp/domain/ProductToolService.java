package com.mirador.mcp.domain;

import com.mirador.product.Product;
import com.mirador.product.ProductDto;
import com.mirador.product.ProductRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * MCP tool surface for the {@code product} aggregate.
 *
 * <p>Currently exposes a single read tool — {@code find_low_stock_products}.
 * The other product CRUD lives on the existing controller ; if a follow-up
 * needs admin LLM tools (create / update / delete), they slot in here next
 * to {@link #findLowStockProducts}.
 */
@Service
public class ProductToolService {

    /**
     * Default low-stock threshold when the LLM omits the parameter.
     * Mirrors the runbook value used by Mirador's existing low-stock
     * Grafana alert — 10 units = "we should reorder soon".
     */
    public static final int DEFAULT_THRESHOLD = 10;

    /**
     * Hard cap on rows returned. {@link ProductRepository#findAll()} can
     * grow unbounded ; this filter is applied AFTER the threshold filter
     * so the cap protects against a runaway "set threshold to 1_000_000"
     * call.
     */
    public static final int MAX_RESULTS = 100;

    private final ProductRepository products;

    public ProductToolService(ProductRepository products) {
        this.products = products;
    }

    /**
     * Returns products whose {@code stock_quantity} is strictly below the
     * supplied threshold (or {@link #DEFAULT_THRESHOLD} when omitted).
     * Sorted by remaining stock ascending so the LLM sees the most-urgent
     * items first.
     *
     * @param threshold strict upper bound on stock_quantity ; values ≤ 0
     *                  fall back to {@link #DEFAULT_THRESHOLD}
     * @return DTO list, capped at {@link #MAX_RESULTS}
     */
    @Tool(name = "find_low_stock_products",
            description = "Returns products whose stock_quantity is strictly below the "
                    + "given threshold (default 10). Sorted by remaining stock ascending "
                    + "so the most urgent items appear first. Capped at 100 results.")
    public List<ProductDto> findLowStockProducts(
            @ToolParam(required = false, description = "Threshold below which a product is "
                    + "considered low-stock. Default 10.")
            int threshold
    ) {
        int effective = threshold <= 0 ? DEFAULT_THRESHOLD : threshold;
        // Reading the full table in-memory is acceptable here : the demo
        // catalogue is small (< 100 rows) and adding a dedicated JPQL
        // query would commit to schema details (stockQuantity is the
        // current name — likely to change before GA) without much gain.
        return products.findAll().stream()
                .filter(p -> p.getStockQuantity() != null && p.getStockQuantity() < effective)
                .sorted((a, b) -> Integer.compare(a.getStockQuantity(), b.getStockQuantity()))
                .limit(MAX_RESULTS)
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    /**
     * Adapts a JPA {@link Product} to the {@link ProductDto} record used
     * by the rest of the API.
     */
    private ProductDto toDto(Product p) {
        return ProductDto.from(p);
    }
}
