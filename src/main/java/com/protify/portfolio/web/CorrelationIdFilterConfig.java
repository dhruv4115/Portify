package com.protify.portfolio.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * D1-B4 — registers {@link CorrelationIdFilter} at the highest possible precedence so it runs
 * before Spring Security's filter chain (order {@code HIGHEST_PRECEDENCE + 100}). A 401 must be
 * traceable by correlation id just like every other response.
 */
@Configuration
public class CorrelationIdFilterConfig {

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration() {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
