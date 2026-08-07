package com.protify.portfolio.api.transaction;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.protify.portfolio.api.mapper.InstrumentMapper;
import com.protify.portfolio.api.mapper.TransactionMapper;
import com.protify.portfolio.api.user.CurrentUser;
import com.protify.portfolio.api.user.CurrentUserResolver;
import com.protify.portfolio.common.error.NotFoundException;
import com.protify.portfolio.fx.CachingFxRateService;
import com.protify.portfolio.instrument.InstrumentRepository;
import com.protify.portfolio.portfolio.PortfolioService;
import com.protify.portfolio.transaction.RecordTransactionCommand;
import com.protify.portfolio.transaction.RowRejectedException;
import com.protify.portfolio.transaction.TransactionImportService;
import com.protify.portfolio.transaction.TransactionImportService.ImportResult;
import com.protify.portfolio.transaction.TransactionRepository;
import com.protify.portfolio.transaction.TransactionService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * API_CONTRACT.md §8.1. The parser is the real bean — the endpoint's job is to turn a file into a
 * summary, and a mocked parser would leave nothing of that under test. Only the write path below
 * it is stubbed, so these tests are about the HTTP contract: what status a rejected file gets, and
 * whether a row error can be traced back to a line in the user's file.
 */
@WebMvcTest(controllers = TransactionController.class)
@Import({TransactionQueryService.class, TransactionMapper.class, InstrumentMapper.class,
        TransactionCsvParser.class})
class TransactionImportControllerTest {

    private static final CurrentUser USER = new CurrentUser(42L, "dhruv@example.com", "Dhruv", null, Instant.EPOCH);
    private static final long PORTFOLIO_ID = 7L;
    /** {@code WebConfig} prefixes every controller with {@code /api/v1}; the slice loads it. */
    private static final String URL = "/api/v1/portfolios/" + PORTFOLIO_ID + "/transactions/import";

    private static final String HEADER = "Date,Type,Symbol,Quantity,Price,Currency,Fees,Note\n";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransactionService transactionService;
    @MockitoBean
    private TransactionImportService transactionImportService;
    @MockitoBean
    private PortfolioService portfolioService;
    @MockitoBean
    private TransactionRepository transactionRepository;
    @MockitoBean
    private InstrumentRepository instrumentRepository;
    @MockitoBean
    private CachingFxRateService fxRateService;
    @MockitoBean
    private CurrentUserResolver currentUserResolver;

    @BeforeEach
    void signIn() {
        given(currentUserResolver.resolve()).willReturn(USER);
    }

    private static MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "ledger.csv", "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }

    private void givenImports(int count, String... warnings) {
        given(transactionImportService.importAll(anyLong(), anyLong(), any(), anyBoolean()))
                .willReturn(new ImportResult(count, List.of(warnings)));
    }

    @Test
    void importsAValidFileAndReportsWhatItWrote() throws Exception {
        givenImports(2);

        mockMvc.perform(multipart(URL).file(csv(HEADER
                        + "2026-06-15,DEPOSIT,,,5000,USD,,\n"
                        + "2026-06-16,BUY,AAPL,10,150.25,USD,1.00,\n")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(false))
                .andExpect(jsonPath("$.totalRows").value(2))
                .andExpect(jsonPath("$.imported").value(2))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void passesWarningsStraightThrough() throws Exception {
        givenImports(1, "These transactions leave the portfolio's cash balance negative.");

        mockMvc.perform(multipart(URL).file(csv(HEADER + "2026-06-16,BUY,AAPL,10,150.25,USD,0,")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings[0]").value(
                        "These transactions leave the portfolio's cash balance negative."));
    }

    @Test
    void dryRunIsForwardedAndEchoedBack() throws Exception {
        givenImports(1);

        mockMvc.perform(multipart(URL).file(csv(HEADER + "2026-06-15,DEPOSIT,,,5000,USD,,"))
                        .param("dryRun", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.imported").value(1));

        verify(transactionImportService).importAll(eq(USER.id()), eq(PORTFOLIO_ID), any(), eq(true));
    }

    /**
     * The design decision worth pinning: a file whose rows are wrong is a 200 carrying the reasons,
     * not a 4xx. A ProblemDetail cannot say "lines 3 and 4, for these two different reasons", and
     * that list is the only thing that lets someone fix their file.
     */
    @Test
    void aFileWithBadRowsIs200WithEveryReasonAndNoWrite() throws Exception {
        mockMvc.perform(multipart(URL).file(csv(HEADER
                        + "2026-06-15,DEPOSIT,,,5000,USD,,\n"
                        + "2026-06-16,PURCHASE,AAPL,10,150.25,USD,0,\n"
                        + "nope,BUY,AAPL,10,150.25,USD,0,\n")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(3))
                .andExpect(jsonPath("$.imported").value(0))
                .andExpect(jsonPath("$.failed").value(2))
                .andExpect(jsonPath("$.errors[0].line").value(3))
                .andExpect(jsonPath("$.errors[1].line").value(4));

        verify(transactionImportService, never()).importAll(anyLong(), anyLong(), any(), anyBoolean());
    }

    @Test
    void translatesARejectedRowsIndexBackIntoItsFileLine() throws Exception {
        // The service speaks in positions within the batch; the user reads line numbers.
        given(transactionImportService.importAll(anyLong(), anyLong(), any(), anyBoolean()))
                .willThrow(new RowRejectedException(1, "No instrument found with symbol \"NOPE\"."));

        mockMvc.perform(multipart(URL).file(csv(HEADER
                        + "2026-06-15,DEPOSIT,,,5000,USD,,\n"
                        + "2026-06-16,BUY,NOPE,10,150.25,USD,0,\n")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(0))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.errors[0].line").value(3))
                .andExpect(jsonPath("$.errors[0].message").value("No instrument found with symbol \"NOPE\"."));
    }

    @Test
    void passesTheParsedCommandsToTheImporter() throws Exception {
        givenImports(1);

        mockMvc.perform(multipart(URL).file(csv(HEADER + "2026-06-15,DEPOSIT,,,5000,USD,,")))
                .andExpect(status().isOk());

        verify(transactionImportService).importAll(eq(USER.id()), eq(PORTFOLIO_ID),
                org.mockito.ArgumentMatchers.argThat(commands -> {
                    RecordTransactionCommand only = commands.get(0);
                    return commands.size() == 1
                            && only.symbol() == null
                            && only.price().compareTo(new java.math.BigDecimal("5000")) == 0;
                }), eq(false));
    }

    // ---- 4xx: the file, or the portfolio, rather than the rows -----------------------------

    @Test
    void anEmptyUploadIs400AgainstTheFileField() throws Exception {
        mockMvc.perform(multipart(URL).file(csv("")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("file"));
    }

    @Test
    void aFileWithNoRecognisableColumnsIs400() throws Exception {
        mockMvc.perform(multipart(URL).file(csv("alpha,beta\n1,2")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("missing required column")));
    }

    @Test
    void someoneElsesPortfolioIs404NotAnImportSummary() throws Exception {
        given(transactionImportService.importAll(anyLong(), anyLong(), any(), anyBoolean()))
                .willThrow(new NotFoundException("portfolio", PORTFOLIO_ID));

        mockMvc.perform(multipart(URL).file(csv(HEADER + "2026-06-15,DEPOSIT,,,5000,USD,,")))
                .andExpect(status().isNotFound());
    }
}
