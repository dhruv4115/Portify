package com.protify.portfolio.support;

import java.util.List;

/** Hand-rolled paged result to match {@link Pageable} — see that class for why. */
public record Page<T>(List<T> content, int page, int size, long totalElements) {

    public int totalPages() {
        return size == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
    }
}
