package com.protify.portfolio.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * D3-B1/D3-B3 need {@code @Scheduled} methods ({@code PriceRefreshScheduler},
 * {@code FxRefreshScheduler}). {@code @EnableScheduling} has to live somewhere that is always
 * component-scanned — here, in Dev B's own {@code config} package, rather than added to
 * {@code PortfolioApplication} (Dev C's file, not to be touched — CLAUDE.md).
 *
 * <p>The {@link Clock} bean exists so every class that needs "now" (schedulers, the rate
 * limiter/circuit breaker) takes it as a constructor dependency instead of calling
 * {@code Clock.systemUTC()} or {@code LocalDate.now()} directly — tests then inject a
 * controllable clock instead of using {@code Thread.sleep} to wait out real time.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
