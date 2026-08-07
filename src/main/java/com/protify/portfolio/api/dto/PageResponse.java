package com.protify.portfolio.api.dto;

import com.protify.portfolio.support.Page;
import java.util.List;

/** API_CONTRACT.md §0.6 pagination envelope. Not used by any Day 2 endpoint (portfolios and
 * instruments are both un-paged), but built today so tomorrow's {@code GET
 * /portfolios/{id}/transactions} has it ready. */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    public static <T> PageResponse<T> from(Page<T> page) {
        int totalPages = page.totalPages();
        boolean isLast = page.page() >= totalPages - 1;
        return new PageResponse<>(
                page.content(), page.page(), page.size(), page.totalElements(),
                totalPages, page.page() == 0, isLast);
    }
}
