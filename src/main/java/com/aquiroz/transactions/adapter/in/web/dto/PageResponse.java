package com.aquiroz.transactions.adapter.in.web.dto;

import com.aquiroz.transactions.domain.port.PageResult;
import java.util.List;
import java.util.function.Function;

public record PageResponse<T>(List<T> items, int page, int limit, boolean hasNext) {

    public static <S, T> PageResponse<T> from(PageResult<S> result, Function<S, T> mapper) {
        return new PageResponse<>(result.items().stream().map(mapper).toList(), result.page(), result.limit(),
                result.hasNext());
    }
}
