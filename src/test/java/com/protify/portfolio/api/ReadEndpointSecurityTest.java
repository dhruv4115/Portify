package com.protify.portfolio.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.protify.portfolio.api.holding.HoldingController;
import com.protify.portfolio.api.holding.HoldingViewService;
import com.protify.portfolio.api.mapper.ValuationMapper;
import com.protify.portfolio.api.user.CurrentUserResolver;
import com.protify.portfolio.api.valuation.PerformanceController;
import com.protify.portfolio.api.valuation.PerformanceViewService;
import com.protify.portfolio.api.valuation.ValuationController;
import com.protify.portfolio.security.SecurityConfig;
import com.protify.portfolio.valuation.ValuationService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The "no token -> 401" case for the three Day 3 read endpoints (day-3-dev-C.md D3-C2 and
 * D3-C3). Their own slice tests run without a filter chain — Boot's security auto-configuration
 * is excluded project-wide, see {@code application.properties} — so none of them can produce a
 * genuine 401; this class imports the real {@link SecurityConfig} and does.
 *
 * <p>Parameterised over the endpoints rather than written three times, which is also the shape
 * Day 4's {@code CrossUserAccessIT} takes for the 404 half of the same question.
 */
@WebMvcTest(controllers = {HoldingController.class, ValuationController.class, PerformanceController.class})
@Import({SecurityConfig.class, ReadEndpointSecurityTest.JwtDecoderTestConfig.class})
class ReadEndpointSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HoldingViewService holdingViewService;

    @MockitoBean
    private ValuationService valuationService;

    @MockitoBean
    private ValuationMapper valuationMapper;

    @MockitoBean
    private PerformanceViewService performanceViewService;

    @MockitoBean
    private CurrentUserResolver currentUserResolver;

    /** Never invoked — no test here presents a token. It exists so the resource server can be
     * configured without fetching Google's real JWKS. */
    @TestConfiguration
    static class JwtDecoderTestConfig {
        @Bean
        @Primary
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new BadJwtException("no test in this class presents a token");
            };
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/portfolios/7/holdings",
            "/api/v1/portfolios/7/valuation",
            "/api/v1/portfolios/7/performance"
    })
    void shouldReturn401WithNoToken(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/unauthenticated"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/portfolios/7/holdings",
            "/api/v1/portfolios/7/valuation",
            "/api/v1/portfolios/7/performance"
    })
    void shouldRejectBeforeAnyUserScopedCodeRuns(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());

        verify(currentUserResolver, never()).resolve();
        verify(holdingViewService, never()).list(anyLong(), anyLong(), any(), anyBoolean());
        verify(valuationService, never()).valuate(anyLong(), anyLong(), any(), any());
        verify(performanceViewService, never()).get(anyLong(), anyLong(), any(), any(), any(), any());
    }
}
