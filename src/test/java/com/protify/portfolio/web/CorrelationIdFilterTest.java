package com.protify.portfolio.web;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void shouldEchoSuppliedCorrelationId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "given-id-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).isEqualTo("given-id-123");
        verify(chain).doFilter(request, response);
    }

    @Test
    void shouldGenerateCorrelationIdWhenAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        String generated = response.getHeader(CorrelationIdFilter.HEADER_NAME);
        assertThat(generated).isNotBlank();
        assertThat(java.util.UUID.fromString(generated)).isNotNull();
    }

    @Test
    void shouldGenerateCorrelationIdWhenHeaderIsBlank() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).isNotBlank().isNotEqualTo("   ");
    }

    @Test
    void shouldPutCorrelationIdInMdcDuringTheRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "mdc-check-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (req, res) -> assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo("mdc-check-id");

        filter.doFilter(request, response, chain);
    }

    @Test
    void shouldClearMdcAfterRequestCompletes() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void shouldClearMdcEvenWhenDownstreamThrows() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        doThrow(new RuntimeException("boom")).when(chain).doFilter(any(), any());

        try {
            filter.doFilter(request, response, chain);
        } catch (RuntimeException expected) {
            // a leaked MDC entry means the next request on this pooled thread logs the wrong id
        }

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
