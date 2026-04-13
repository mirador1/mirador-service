package com.example.customerservice.customer;

import java.util.List;

/**
 * Cursor-based pagination response — alternative to offset-based {@code Page<T>}.
 *
 * <p>Cursor pagination uses the last element's ID as a bookmark for the next page,
 * avoiding the performance pitfalls of {@code OFFSET} on large datasets (which must
 * skip N rows to reach the target page). With a cursor, the query uses
 * {@code WHERE id > :cursor} and always scans from an index seek.
 *
 * @param content the items on this page
 * @param nextCursor the ID to pass as {@code cursor} for the next page, or {@code null} if last page
 * @param hasNext whether more items exist after this page
 * @param size the requested page size
 */
public record CursorPage<T>(
        List<T> content,
        Long nextCursor,
        boolean hasNext,
        int size
) {}
