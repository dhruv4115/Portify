package com.protify.portfolio.insights;

import com.protify.portfolio.common.enums.CurrencyCode;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * D5-B2 — {@code InsightsClientTest}: {@link MockRestServiceServer} against the client's own
 * {@link RestClient}. No test here calls a real insights service.
 */
class InsightsClientTest {

    private static final PortfolioSummary CONCENTRATED_SUMMARY = new PortfolioSummary(
            CurrencyCode.INR, new BigDecimal("100000"), new BigDecimal("5000"),
            List.of(
                    new HoldingSummary("AAPL", CurrencyCode.USD, new BigDecimal("57000"), new BigDecimal("50000")),
                    new HoldingSummary("RELIANCE", CurrencyCode.INR, new BigDecimal("20000"), new BigDecimal("25000"))
            ));

    @Test
    void flagOffMeansTheServiceIsNeverCalled() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        InsightsClient client = new InsightsClient(false, builder.build());

        InsightsResult result = client.generate(CONCENTRATED_SUMMARY, "1M", "concise");

        assertThat(result.engine()).isEqualTo(Engine.RULE_BASED);
        server.verify(); // no expectations were registered — verify() fails if any request was made
    }

    @Test
    void serviceDownReturnsARuleBasedSummaryAndNoExceptionEscapes() {
        // Points at a real-looking but never-listening URL — connection refused, not a mock.
        RestClient restClient = RestClient.builder().baseUrl("http://localhost:1").build();
        InsightsClient client = new InsightsClient(true, restClient);

        assertThatCode(() -> {
            InsightsResult result = client.generate(CONCENTRATED_SUMMARY, "1M", "concise");
            assertThat(result.engine()).isEqualTo(Engine.RULE_BASED);
            assertThat(result.summary()).isNotBlank();
        }).doesNotThrowAnyException();
    }

    @Test
    void aTimeoutFallsBackToRuleBasedWithoutThrowing() {
        // Same convention as YahooMarketDataProviderTest.aTimeoutDoesNotThrow: simulate the
        // timeout by throwing IOException from the mocked transport, rather than waiting out
        // the real connect/read timeout against an unreachable host — which is now measured in
        // seconds, because a hosted model needs room to answer (insights.client.timeout-ms).
        RestClient.Builder builder = RestClient.builder().baseUrl("http://insights.local");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ResponseCreator simulatedTimeout = request -> {
            throw new IOException("simulated read timeout");
        };
        server.expect(requestTo("http://insights.local/insights"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(simulatedTimeout);
        InsightsClient client = new InsightsClient(true, builder.build());

        InsightsResult result = client.generate(CONCENTRATED_SUMMARY, "1M", "concise");

        assertThat(result.engine()).isEqualTo(Engine.RULE_BASED);
        server.verify();
    }

    @Test
    void aServerErrorFallsBackToRuleBasedWithoutThrowing() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://insights.local");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://insights.local/insights"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withServerError());
        InsightsClient client = new InsightsClient(true, builder.build());

        InsightsResult result = client.generate(CONCENTRATED_SUMMARY, "1M", "concise");

        assertThat(result.engine()).isEqualTo(Engine.RULE_BASED);
        server.verify();
    }

    @Test
    void enabledAndServiceRespondsReturnsTheServicesResultAsIs() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://insights.local");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://insights.local/insights"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "summary": "A generated summary.",
                          "highlights": [{"type":"CONCENTRATION","severity":"MEDIUM","message":"AAPL is 57.0% of market value."}],
                          "engine": "LLM"
                        }
                        """, MediaType.APPLICATION_JSON));
        InsightsClient client = new InsightsClient(true, builder.build());

        InsightsResult result = client.generate(CONCENTRATED_SUMMARY, "1M", "concise");

        assertThat(result.engine()).isEqualTo(Engine.LLM);
        assertThat(result.summary()).isEqualTo("A generated summary.");
        server.verify();
    }

    @Test
    void isEnabledReflectsTheConstructorFlag() {
        RestClient restClient = RestClient.builder().build();

        assertThat(new InsightsClient(true, restClient).isEnabled()).isTrue();
        assertThat(new InsightsClient(false, restClient).isEnabled()).isFalse();
    }
}
