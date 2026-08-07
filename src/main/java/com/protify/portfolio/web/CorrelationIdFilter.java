package com.protify.portfolio.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * D1-B4 — reads {@value #HEADER_NAME} from the request, or mints a UUID when absent, puts it
 * in the SLF4J MDC under {@value #MDC_KEY} (agreed with Dev C's {@code GlobalExceptionHandler})
 * so every log line, including a 401 from the security filter chain, is traceable, and echoes
 * it on the response header.
 *
 * <p>Registered with the highest possible precedence (see {@link CorrelationIdFilterConfig}) so
 * it runs before Spring Security's filter chain — a 401 must be traceable too.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String correlationId = request.getHeader(HEADER_NAME);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        response.setHeader(HEADER_NAME, correlationId);
        MDC.put(MDC_KEY, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
