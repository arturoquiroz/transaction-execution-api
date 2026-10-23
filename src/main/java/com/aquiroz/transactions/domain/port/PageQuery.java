package com.aquiroz.transactions.domain.port;

public record PageQuery(int page, int limit) {

    public static final int MAX_LIMIT = 100;

    public PageQuery {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0: " + page);
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT + ": " + limit);
        }
    }

    // long: page * limit can overflow int for large page numbers
    public long offset() {
        return (long) page * limit;
    }
}
