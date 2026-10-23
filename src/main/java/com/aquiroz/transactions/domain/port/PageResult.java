package com.aquiroz.transactions.domain.port;

import java.util.List;

/**
 * One page of results. hasNext comes from fetching limit + 1 rows instead of a COUNT(*) (LLD §5.10).
 */
public record PageResult<T>(List<T> items, int page, int limit, boolean hasNext) {

    public PageResult {
        items = List.copyOf(items);
    }
}
