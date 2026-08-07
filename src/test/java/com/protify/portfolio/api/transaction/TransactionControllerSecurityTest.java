package com.protify.portfolio.api.transaction;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.protify.portfolio.api.mapper.TransactionMapper;
import com.protify.portfolio.api.user.CurrentUserResolver;
import com.protify.portfolio.security.SecurityConfig;
import com.protify.portfolio.transaction.TransactionImportService;
import com.protify.portfolio.transaction.TransactionService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The "no token -> 401" case from day-3-dev-C.md D3-C1's list, for all three verbs.
 * {@code TransactionControllerTest} runs without a filter chain (like every other {@code
 * @WebMvcTest} slice in this codebase — see {@code application.properties} on why Boot's own
 * security auto-configuration is excluded), so it has no mechanism to produce a genuine 401;
 * this class imports the real {@link SecurityConfig} and does.
 *
 * <p>Beyond the status code it asserts the {@code ProblemDetail} shape and that the request never
 * reaches the service — an unauthenticated call must be rejected before any user-scoped code
 * runs, not after it decides there is nothing to return.
 */
@WebMvcTest(controllers = TransactionController.class)
@Import({SecurityConfig.class, TransactionControllerSecurityTest.JwtDecoderTestConfig.class})
class TransactionControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransactionService transactionService;

    @MockitoBean
    private TransactionQueryService transactionQueryService;

    @MockitoBean
    private TransactionImportService transactionImportService;

    @MockitoBean
    private TransactionCsvParser csvParser;

    @MockitoBean
    private TransactionMapper transactionMapper;

    @MockitoBean
    private CurrentUserResolver currentUserResolver;

    /** No test here presents a token, so this is never invoked — it exists only so the resource
     * server can be configured without reaching out to Google for a real JWKS. */
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

    @Test
    void shouldReturn401WhenListingWithNoToken() throws Exception {
        mockMvc.perform(get("/api/v1/portfolios/7/transactions"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/unauthenticated"))
                .andExpect(jsonPath("$.correlationId").exists());

        verify(transactionQueryService, never()).list(anyLong(), anyLong(), any(), any());
    }

    @Test
    void shouldReturn401WhenCreatingWithNoToken() throws Exception {
        mockMvc.perform(post("/api/v1/portfolios/7/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"BUY","symbol":"AAPL","quantity":"3","price":"228.10",
                                 "currency":"USD","executedAt":"2026-07-30T09:00:00Z"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/unauthenticated"));

        verify(transactionService, never()).record(anyLong(), anyLong(), any());
    }

    @Test
    void shouldReturn401WhenImportingWithNoToken() throws Exception {
        // A bulk write is the last endpoint that should be reachable unauthenticated. The file
        // must not even be parsed: rejection happens in the filter chain, before the controller.
        mockMvc.perform(multipart("/api/v1/portfolios/7/transactions/import")
                        .file(new MockMultipartFile("file", "ledger.csv", "text/csv",
                                "Date,Type,Price,Currency\n2026-06-15,DEPOSIT,100,USD".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/unauthenticated"));

        verify(csvParser, never()).parse(any());
        verify(transactionImportService, never()).importAll(anyLong(), anyLong(), any(), anyBoolean());
    }

    @Test
    void shouldReturn401WhenDeletingWithNoToken() throws Exception {
        mockMvc.perform(delete("/api/v1/portfolios/7/transactions/91"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/unauthenticated"));

        verify(transactionService, never()).delete(anyLong(), anyLong(), anyLong());
    }
}
