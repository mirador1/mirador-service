package com.example.customerservice.customer;

import java.util.List;

/**
 * Response body for {@code POST /customers/batch}.
 *
 * <p>Reports the outcome of a bulk import: how many succeeded, how many failed,
 * the created customers, and any errors keyed by their index in the input array.
 */
public record BatchImportResult(
        int total,
        int created,
        int failed,
        List<CustomerDto> customers,
        List<BatchError> errors
) {
    public record BatchError(int index, String name, String reason) {}
}
