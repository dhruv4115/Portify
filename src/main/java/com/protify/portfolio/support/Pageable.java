package com.protify.portfolio.support;

/**
 * Hand-rolled paging request — no Spring Data on the classpath (CLAUDE.md: no JPA, no
 * Hibernate, no Spring Data) and {@code pom.xml} is frozen on a feature branch, so
 * {@code org.springframework.data.domain.Pageable} isn't an option even as a value type.
 */
public record Pageable(int page, int size) {

    public Pageable {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative: " + page);
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive: " + size);
        }
    }

    public int offset() {
        return page * size;
    }
}
