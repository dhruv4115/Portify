package com.protify.portfolio.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The application shell. {@code scanBasePackages} is explicit rather than left to the default
 * (which would only scan {@code com.protify.portfolio.api} and below) because {@code common},
 * {@code support} and every other dev's package sit alongside {@code api}, not under it —
 * {@code core} and {@code platform} beans need to be picked up too.
 */
@SpringBootApplication(scanBasePackages = "com.protify.portfolio")
public class PortfolioApplication {

    public static void main(String[] args) {
        SpringApplication.run(PortfolioApplication.class, args);
    }
}
