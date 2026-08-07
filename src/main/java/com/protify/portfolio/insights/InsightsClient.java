package com.protify.portfolio.insights;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * D5-B2 — the Java side of the insights feature. Behind {@code FEATURES_INSIGHTS_ENABLED}
 * (default {@code false}): when disabled, {@link #generate} never makes a network call at all
 * and returns the Java rule-based fallback directly — Dev C's future controller checks
 * {@link #isEnabled()} to answer {@code 501} instead of ever calling this class, but this class
 * is safe to call either way (defence in depth, the same "degrade, never break" pattern as
 * {@code CachingMarketDataService}/{@code CachingFxRateService}).
 *
 * <p>On any failure once enabled — timeout, connection refused, non-2xx, malformed body — this
 * falls back to a rule-based summary generated in Java (not just the Python service's own
 * rule-based path): the whole feature degrades twice before a caller ever sees a failure.
 * <b>Never throws, never returns null, never a 500.</b>
 */
@Component
public class InsightsClient {

    private static final Logger log = LoggerFactory.getLogger(InsightsClient.class);

    /**
     * Must stay <b>above</b> the Python service's own {@code INSIGHTS_LLM_TIMEOUT_SECONDS}, with
     * margin for its rule-based work and the hop itself. If this is the smaller of the two, this
     * client abandons the request and returns its Java fallback while the service is still
     * waiting on the provider — the model answers into a closed connection, and the caller is
     * billed for a summary nobody ever sees.
     *
     * <p>The old hard-coded 2000 was below any realistic hosted-model latency, so with a valid
     * key the LLM path could not win and every response was {@code RULE_BASED}. That failure is
     * invisible by design — the fallback works — which is why this is now configurable and
     * generous rather than tight.
     */
    private static final int DEFAULT_TIMEOUT_MILLIS = 30_000;

    private final boolean enabled;
    private final RestClient restClient;

    @Autowired
    public InsightsClient(@Value("${features.insights.enabled:false}") boolean enabled,
                           @Value("${insights.service.url:http://localhost:8000}") String serviceUrl,
                           @Value("${insights.client.timeout-ms:" + DEFAULT_TIMEOUT_MILLIS + "}") int timeoutMillis) {
        this(enabled, defaultRestClient(serviceUrl, timeoutMillis));
    }

    /** Test-only seam: inject a {@link RestClient} pointed at a {@link
     * org.springframework.test.web.client.MockRestServiceServer} or an unreachable URL. */
    InsightsClient(boolean enabled, RestClient restClient) {
        this.enabled = enabled;
        this.restClient = restClient;
    }

    private static RestClient defaultRestClient(String serviceUrl, int timeoutMillis) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // Connect stays short: refused or unroutable is known immediately, and waiting the full
        // read budget to discover the service is simply not running helps nobody. Only the read
        // has to accommodate a model thinking.
        factory.setConnectTimeout(Math.min(2000, timeoutMillis));
        factory.setReadTimeout(timeoutMillis);
        return RestClient.builder().baseUrl(serviceUrl).requestFactory(factory).build();
    }

    /** Whether the feature is turned on at all — Dev C's controller uses this to decide
     * {@code 501} vs. calling {@link #generate}; API_CONTRACT.md §18. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * The deterministic reading, asked for on purpose rather than reached by falling back.
     *
     * <p>Exposed so a caller can offer both engines' summaries side by side (API_CONTRACT.md
     * §18's {@code variants}). It is the same generator the failure path uses, deliberately —
     * a second implementation for the "chosen" case would be a second thing to keep true.
     *
     * <p>Pure and offline: no network, no clock, no randomness. Computing it alongside an LLM
     * call costs nothing, which is why both can be returned from one request.
     */
    public InsightsResult ruleBased(PortfolioSummary summary) {
        return RuleBasedInsights.generate(summary);
    }

    /**
     * Never throws. Flag off, or any failure calling the Python service, both resolve to a
     * Java-generated rule-based {@link InsightsResult} with {@link Engine#RULE_BASED}.
     */
    public InsightsResult generate(PortfolioSummary summary, String horizon, String tone) {
        if (!enabled) {
            return RuleBasedInsights.generate(summary);
        }

        try {
            InsightsResult result = restClient.post()
                    .uri("/insights")
                    .body(new InsightsRequest(summary, horizon, tone))
                    .retrieve()
                    .body(InsightsResult.class);
            if (result != null) {
                return result;
            }
            log.warn("Insights service returned an empty body; falling back to rule-based");
        } catch (RestClientException e) {
            log.warn("Insights service call failed: {}", e.getMessage());
        }
        return RuleBasedInsights.generate(summary);
    }
}
