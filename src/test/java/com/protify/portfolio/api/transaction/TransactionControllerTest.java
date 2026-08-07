package com.protify.portfolio.api.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.protify.portfolio.api.mapper.InstrumentMapper;
import com.protify.portfolio.api.mapper.TransactionMapper;
import com.protify.portfolio.api.user.CurrentUser;
import com.protify.portfolio.api.user.CurrentUserResolver;
import com.protify.portfolio.common.enums.AssetType;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.FxSource;
import com.protify.portfolio.common.enums.TransactionType;
import com.protify.portfolio.common.error.CurrencyMismatchException;
import com.protify.portfolio.common.error.InsufficientQuantityException;
import com.protify.portfolio.common.error.NotFoundException;
import com.protify.portfolio.common.port.FxQuote;
import com.protify.portfolio.fx.CachingFxRateService;
import com.protify.portfolio.instrument.Instrument;
import com.protify.portfolio.instrument.InstrumentRepository;
import com.protify.portfolio.portfolio.Portfolio;
import com.protify.portfolio.portfolio.PortfolioService;
import com.protify.portfolio.support.Page;
import com.protify.portfolio.support.Pageable;
import com.protify.portfolio.transaction.RecordTransactionResult;
import com.protify.portfolio.transaction.TransactionImportService;
import com.protify.portfolio.transaction.TransactionRepository;
import com.protify.portfolio.transaction.TransactionService;
import com.protify.portfolio.transaction.Txn;
import com.protify.portfolio.transaction.TxnFilter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * day-3-dev-C.md D3-C1, API_CONTRACT.md §7–§9. {@link TransactionQueryService},
 * {@link TransactionMapper} and {@link InstrumentMapper} are the real beans, not mocks — the
 * response shape (native vs base currency, the trade-date FX rate, {@code warnings[]}) is most of
 * what this task delivers, and mocking the thing that builds it would assert nothing. Only the
 * data sources below it are stubbed.
 *
 * <p>"No token -> 401" lives in {@code TransactionControllerSecurityTest}: it needs the real
 * filter chain, which this slice deliberately does not load.
 */
@WebMvcTest(controllers = TransactionController.class)
@Import({TransactionQueryService.class, TransactionMapper.class, InstrumentMapper.class,
        TransactionCsvParser.class})
class TransactionControllerTest {

    private static final CurrentUser USER = new CurrentUser(42L, "dhruv@example.com", "Dhruv", null, Instant.EPOCH);
    private static final long PORTFOLIO_ID = 7L;

    private static final Instrument AAPL = new Instrument(
            1L, "AAPL", "Apple Inc.", AssetType.STOCK, CurrencyCode.USD, "NASDAQ", "Technology", Instant.EPOCH);
    private static final Instrument RELIANCE = new Instrument(
            2L, "RELIANCE", "Reliance Industries Ltd", AssetType.STOCK, CurrencyCode.INR, "NSE", "Energy", Instant.EPOCH);

    /** USD -> INR on 11 Jun 2026, the rate API_CONTRACT.md §7's worked example uses. */
    private static final BigDecimal USD_TO_INR = new BigDecimal("86.90000000");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransactionService transactionService;

    /** The import endpoint's collaborator — exercised in {@link TransactionImportControllerTest},
     * present here only so the controller can be constructed. */
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
    void stubOwnedInrPortfolio() {
        given(currentUserResolver.resolve()).willReturn(USER);
        given(portfolioService.getOrThrow(USER.id(), PORTFOLIO_ID)).willReturn(new Portfolio(
                PORTFOLIO_ID, USER.id(), "Growth", CurrencyCode.INR,
                Instant.parse("2026-02-14T11:03:00Z"), Instant.parse("2026-07-28T10:15:00Z")));
        givenRate(CurrencyCode.INR, CurrencyCode.INR, BigDecimal.ONE);
        givenRate(CurrencyCode.USD, CurrencyCode.INR, USD_TO_INR);
    }

    private void givenRate(CurrencyCode from, CurrencyCode to, BigDecimal rate) {
        given(fxRateService.rate(eq(from), eq(to), any(LocalDate.class))).willAnswer(invocation ->
                Optional.of(new FxQuote(from, to, invocation.getArgument(2), rate, FxSource.MANUAL)));
    }

    private void givenInstrument(Instrument instrument) {
        given(instrumentRepository.findBySymbol(instrument.symbol())).willReturn(Optional.of(instrument));
        given(instrumentRepository.findById(instrument.id())).willReturn(Optional.of(instrument));
    }

    private void givenRecorded(long txnId, String... warnings) {
        given(transactionService.record(eq(USER.id()), eq(PORTFOLIO_ID), any()))
                .willReturn(new RecordTransactionResult(txnId, List.of(warnings)));
    }

    private static Txn txn(long id, Instrument instrument, TransactionType type, String quantity,
            String price, String fees, CurrencyCode currency, String executedAt) {
        return new Txn(id, PORTFOLIO_ID, instrument == null ? null : instrument.id(), type,
                new BigDecimal(quantity), new BigDecimal(price), new BigDecimal(fees), currency,
                Instant.parse(executedAt), null, Instant.EPOCH);
    }

    // ---- POST: the customer's "add" ------------------------------------------------------

    @Test
    void shouldCreateBuyWith201LocationHeaderAndEmptyWarnings() throws Exception {
        givenInstrument(RELIANCE);
        givenRecorded(91L);

        mockMvc.perform(post("/api/v1/portfolios/7/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"BUY","symbol":"RELIANCE","quantity":"10","price":"1450.25",
                                 "currency":"INR","fees":"24.50","executedAt":"2026-07-28T10:15:00Z",
                                 "note":"Post-results add"}"""))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/v1/portfolios/7/transactions/91")))
                .andExpect(jsonPath("$.id").value(91))
                .andExpect(jsonPath("$.type").value("BUY"))
                .andExpect(jsonPath("$.instrument.symbol").value("RELIANCE"))
                .andExpect(jsonPath("$.quantity").value("10.000000"))
                .andExpect(jsonPath("$.price.amount").value("1450.2500"))
                .andExpect(jsonPath("$.fees.amount").value("24.5000"))
                // 10 x 1450.25 + 24.50, and the portfolio is already in INR
                .andExpect(jsonPath("$.totalNative.amount").value("14527.0000"))
                .andExpect(jsonPath("$.totalNative.currency").value("INR"))
                .andExpect(jsonPath("$.totalBase.amount").value("14527.0000"))
                .andExpect(jsonPath("$.fxRateApplied").value(nullValue()))
                .andExpect(jsonPath("$.note").value("Post-results add"))
                .andExpect(jsonPath("$.warnings").isArray())
                .andExpect(jsonPath("$.warnings", hasSize(0)));
    }

    @Test
    void shouldCreateSellAndReportTheFxRateOnTheExecutionDateNotToday() throws Exception {
        givenInstrument(AAPL);
        givenRecorded(92L);

        mockMvc.perform(post("/api/v1/portfolios/7/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"SELL","symbol":"AAPL","quantity":"5","price":"228.10",
                                 "currency":"USD","fees":"1.99","executedAt":"2026-06-11T14:30:00Z"}"""))
                .andExpect(status().isCreated())
                // native stays USD: 5 x 228.10 - 1.99
                .andExpect(jsonPath("$.totalNative.amount").value("1138.5100"))
                .andExpect(jsonPath("$.totalNative.currency").value("USD"))
                // base is the portfolio's INR, converted at the 11 Jun rate
                .andExpect(jsonPath("$.totalBase.amount").value("98936.5190"))
                .andExpect(jsonPath("$.totalBase.currency").value("INR"))
                .andExpect(jsonPath("$.fxRateApplied").value("86.90000000"));
    }

    @Test
    void shouldCreateDepositWithNoInstrumentAndZeroQuantity() throws Exception {
        givenRecorded(93L);

        mockMvc.perform(post("/api/v1/portfolios/7/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"DEPOSIT","price":"25000","currency":"INR",
                                 "executedAt":"2026-07-01T00:00:00Z"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.instrument").value(nullValue()))
                .andExpect(jsonPath("$.quantity").value("0.000000"))
                // for a cash transaction `price` carries the whole amount (§8)
                .andExpect(jsonPath("$.totalNative.amount").value("25000.0000"));
    }

    @Test
    void shouldReject400WhenQuantityIsZero() throws Exception {
        mockMvc.perform(post("/api/v1/portfolios/7/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"BUY","symbol":"AAPL","quantity":"0","price":"228.10",
                                 "currency":"USD","executedAt":"2026-07-30T09:00:00Z"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("quantity"));

        verify(transactionService, never()).record(anyLong(), anyLong(), any());
    }

    @Test
    void shouldReject400WhenQuantityIsNegative() throws Exception {
        mockMvc.perform(post("/api/v1/portfolios/7/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"BUY","symbol":"AAPL","quantity":"-3","price":"228.10",
                                 "currency":"USD","executedAt":"2026-07-30T09:00:00Z"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("quantity"));

        verify(transactionService, never()).record(anyLong(), anyLong(), any());
    }

    @Test
    void shouldReject400WhenExecutedAtIsInTheFuture() throws Exception {
        mockMvc.perform(post("/api/v1/portfolios/7/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"BUY","symbol":"AAPL","quantity":"3","price":"228.10",
                                 "currency":"USD","executedAt":"2099-01-01T00:00:00Z"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("executedAt"));

        verify(transactionService, never()).record(anyLong(), anyLong(), any());
    }

    @Test
    void shouldReject400WhenSymbolIsMissingOnABuy() throws Exception {
        mockMvc.perform(post("/api/v1/portfolios/7/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"BUY","quantity":"3","price":"228.10",
                                 "currency":"USD","executedAt":"2026-07-30T09:00:00Z"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("symbol"))
                .andExpect(jsonPath("$.errors[0].message", containsString("required")));

        verify(transactionService, never()).record(anyLong(), anyLong(), any());
    }

    @Test
    void shouldReject400WhenSymbolIsSuppliedOnADeposit() throws Exception {
        mockMvc.perform(post("/api/v1/portfolios/7/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"DEPOSIT","symbol":"AAPL","price":"25000",
                                 "currency":"INR","executedAt":"2026-07-01T00:00:00Z"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("symbol"))
                .andExpect(jsonPath("$.errors[0].message", containsString("must not be supplied")));

        verify(transactionService, never()).record(anyLong(), anyLong(), any());
    }

    @Test
    void shouldReturn404ForAnUnknownSymbol() throws Exception {
        given(transactionService.record(eq(USER.id()), eq(PORTFOLIO_ID), any()))
                .willThrow(new NotFoundException("instrument", "TSLAA"));

        mockMvc.perform(post("/api/v1/portfolios/7/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"BUY","symbol":"TSLAA","quantity":"3","price":"228.10",
                                 "currency":"USD","executedAt":"2026-07-30T09:00:00Z"}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/instrument-not-found"))
                .andExpect(jsonPath("$.detail", containsString("TSLAA")));
    }

    /** TEST_PLAN.md §4.10's last line: "the 404 body names the symbol but exposes no SQL and no
     * class name." The symbol is what makes the error actionable; anything else is a leak. */
    @Test
    void shouldNotLeakSqlOrClassNamesInTheUnknownSymbol404() throws Exception {
        given(transactionService.record(eq(USER.id()), eq(PORTFOLIO_ID), any()))
                .willThrow(new NotFoundException("instrument", "TSLAA"));

        String body = mockMvc.perform(post("/api/v1/portfolios/7/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"BUY","symbol":"TSLAA","quantity":"3","price":"228.10",
                                 "currency":"USD","executedAt":"2026-07-30T09:00:00Z"}"""))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("TSLAA");
        assertThat(body).doesNotContain("com.protify").doesNotContain("SELECT").doesNotContain("\\tat ");
        assertThat(body).doesNotContainIgnoringCase("sqlexception").doesNotContainIgnoringCase("jdbc");
    }

    /**
     * TEST_PLAN.md §4.1's fractional boundary as the customer meets it: one micro-unit past a
     * fractional holding is a 422 with a field error, not a 500 and not a silent success.
     * {@code ProjectionEngineTest} and {@code TransactionServiceIT} prove the arithmetic; this
     * proves the status code and the shape of the body.
     */
    @Test
    void shouldReturn422WhenSellExceedsAFractionalHoldingByOneMicroUnit() throws Exception {
        given(transactionService.record(eq(USER.id()), eq(PORTFOLIO_ID), any())).willThrow(
                new InsufficientQuantityException("AAPL", new BigDecimal("0.523101"), new BigDecimal("0.523100")));

        mockMvc.perform(post("/api/v1/portfolios/7/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"SELL","symbol":"AAPL","quantity":"0.523101","price":"228.10",
                                 "currency":"USD","executedAt":"2026-07-30T09:00:00Z"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/insufficient-quantity"))
                .andExpect(jsonPath("$.errors[0].field").value("quantity"))
                .andExpect(jsonPath("$.errors[0].message").value("must not exceed holding of 0.523100"));
    }

    @Test
    void shouldReturn422WithAFieldErrorWhenCurrencyDoesNotMatchTheInstrument() throws Exception {
        given(transactionService.record(eq(USER.id()), eq(PORTFOLIO_ID), any()))
                .willThrow(new CurrencyMismatchException(CurrencyCode.USD, CurrencyCode.INR));

        mockMvc.perform(post("/api/v1/portfolios/7/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"BUY","symbol":"AAPL","quantity":"3","price":"228.10",
                                 "currency":"INR","executedAt":"2026-07-30T09:00:00Z"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/currency-mismatch"))
                .andExpect(jsonPath("$.errors[0].field").value("currency"))
                .andExpect(jsonPath("$.errors[0].message").value("must be USD"));
    }

    @Test
    void shouldReturn422WhenSellExceedsHolding() throws Exception {
        given(transactionService.record(eq(USER.id()), eq(PORTFOLIO_ID), any())).willThrow(
                new InsufficientQuantityException("AAPL", new BigDecimal("50"), new BigDecimal("12")));

        mockMvc.perform(post("/api/v1/portfolios/7/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"SELL","symbol":"AAPL","quantity":"50","price":"228.10",
                                 "currency":"USD","executedAt":"2026-07-30T09:00:00Z"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/insufficient-quantity"))
                .andExpect(jsonPath("$.title").value("Insufficient quantity"))
                .andExpect(jsonPath("$.errors[0].field").value("quantity"))
                .andExpect(jsonPath("$.errors[0].message").value("must not exceed holding of 12.000000"));
    }

    @Test
    void shouldReturn201WithAWarningWhenABuyExceedsAvailableCash() throws Exception {
        givenInstrument(RELIANCE);
        givenRecorded(94L, "This transaction leaves the portfolio's cash balance negative.");

        mockMvc.perform(post("/api/v1/portfolios/7/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"BUY","symbol":"RELIANCE","quantity":"10","price":"1450.25",
                                 "currency":"INR","fees":"24.50","executedAt":"2026-07-28T10:15:00Z"}"""))
                // PLAN.md §2.7: a warning, never a rejection
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.warnings", hasSize(1)))
                .andExpect(jsonPath("$.warnings",
                        contains("This transaction leaves the portfolio's cash balance negative.")));
    }

    @Test
    void shouldReturn404NotA403WhenPostingToAnotherUsersPortfolio() throws Exception {
        given(transactionService.record(eq(USER.id()), eq(8L), any()))
                .willThrow(new NotFoundException("portfolio", 8L));

        mockMvc.perform(post("/api/v1/portfolios/8/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"BUY","symbol":"AAPL","quantity":"3","price":"228.10",
                                 "currency":"USD","executedAt":"2026-07-30T09:00:00Z"}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/portfolio-not-found"));
    }

    // ---- GET: paged and filtered ---------------------------------------------------------

    @Test
    void shouldPageTransactions() throws Exception {
        given(instrumentRepository.findById(RELIANCE.id())).willReturn(Optional.of(RELIANCE));
        given(transactionRepository.findByPortfolioFiltered(eq(PORTFOLIO_ID), any(), any())).willReturn(new Page<>(
                List.of(txn(91L, RELIANCE, TransactionType.BUY, "10", "1450.25", "24.50",
                        CurrencyCode.INR, "2026-07-28T10:15:00Z")),
                1, 2, 24L));

        mockMvc.perform(get("/api/v1/portfolios/7/transactions").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(91))
                .andExpect(jsonPath("$.content[0].instrument.symbol").value("RELIANCE"))
                .andExpect(jsonPath("$.content[0].warnings", hasSize(0)))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(24))
                .andExpect(jsonPath("$.totalPages").value(12))
                .andExpect(jsonPath("$.first").value(false))
                .andExpect(jsonPath("$.last").value(false));

        verify(transactionRepository).findByPortfolioFiltered(PORTFOLIO_ID, TxnFilter.none(), new Pageable(1, 2));
    }

    @Test
    void shouldFilterByType() throws Exception {
        given(transactionRepository.findByPortfolioFiltered(eq(PORTFOLIO_ID), any(), any()))
                .willReturn(new Page<>(List.of(), 0, 20, 0L));

        mockMvc.perform(get("/api/v1/portfolios/7/transactions").param("type", "BUY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));

        verify(transactionRepository).findByPortfolioFiltered(
                PORTFOLIO_ID,
                new TxnFilter(TransactionType.BUY, null, null, null),
                new Pageable(0, 20));
    }

    @Test
    void shouldFilterByDateRangeAndSymbol() throws Exception {
        given(transactionRepository.findByPortfolioFiltered(eq(PORTFOLIO_ID), any(), any()))
                .willReturn(new Page<>(List.of(), 0, 20, 0L));

        mockMvc.perform(get("/api/v1/portfolios/7/transactions")
                        .param("symbol", "AAPL")
                        .param("from", "2026-01-01")
                        .param("to", "2026-07-31"))
                .andExpect(status().isOk());

        verify(transactionRepository).findByPortfolioFiltered(
                PORTFOLIO_ID,
                new TxnFilter(null, "AAPL", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 31)),
                new Pageable(0, 20));
    }

    /**
     * {@code q} reaches the repository rather than being applied to the page after loading it.
     * That is the whole point: filtering in the browser can only see the pages already fetched,
     * so a symbol bought two years ago silently returns nothing until the reader has paged back
     * that far.
     */
    @Test
    void shouldPassFreeTextSearchToTheRepository() throws Exception {
        given(transactionRepository.findByPortfolioFiltered(eq(PORTFOLIO_ID), any(), any()))
                .willReturn(new Page<>(List.of(), 0, 20, 0L));

        mockMvc.perform(get("/api/v1/portfolios/7/transactions").param("q", "reliance"))
                .andExpect(status().isOk());

        verify(transactionRepository).findByPortfolioFiltered(
                PORTFOLIO_ID,
                new TxnFilter(null, null, null, null, "reliance"),
                new Pageable(0, 20));
    }

    /** {@code q} narrows alongside the other filters rather than replacing them. */
    @Test
    void shouldCombineFreeTextSearchWithTypeFilter() throws Exception {
        given(transactionRepository.findByPortfolioFiltered(eq(PORTFOLIO_ID), any(), any()))
                .willReturn(new Page<>(List.of(), 0, 20, 0L));

        mockMvc.perform(get("/api/v1/portfolios/7/transactions")
                        .param("type", "BUY")
                        .param("q", "post-results"))
                .andExpect(status().isOk());

        verify(transactionRepository).findByPortfolioFiltered(
                PORTFOLIO_ID,
                new TxnFilter(TransactionType.BUY, null, null, null, "post-results"),
                new Pageable(0, 20));
    }

    /** A search box that has been typed into and cleared again sends {@code q=}, which must
     * mean "no filter" rather than "match the empty string". */
    @Test
    void shouldTreatBlankSearchAsNoFilter() throws Exception {
        given(transactionRepository.findByPortfolioFiltered(eq(PORTFOLIO_ID), any(), any()))
                .willReturn(new Page<>(List.of(), 0, 20, 0L));

        mockMvc.perform(get("/api/v1/portfolios/7/transactions").param("q", "   "))
                .andExpect(status().isOk());

        verify(transactionRepository).findByPortfolioFiltered(
                PORTFOLIO_ID, TxnFilter.none(), new Pageable(0, 20));
    }

    @Test
    void shouldReturn400WhenFromIsAfterTo() throws Exception {
        mockMvc.perform(get("/api/v1/portfolios/7/transactions")
                        .param("from", "2026-07-31")
                        .param("to", "2026-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/invalid-date-range"));

        verify(transactionRepository, never()).findByPortfolioFiltered(anyLong(), any(), any());
    }

    @Test
    void shouldReturn400WhenPageSizeExceedsTheMaximum() throws Exception {
        mockMvc.perform(get("/api/v1/portfolios/7/transactions").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"));
    }

    @Test
    void shouldReturn404NotA403WhenListingAnotherUsersPortfolio() throws Exception {
        given(portfolioService.getOrThrow(USER.id(), 8L)).willThrow(new NotFoundException("portfolio", 8L));

        mockMvc.perform(get("/api/v1/portfolios/8/transactions"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/portfolio-not-found"));

        verify(transactionRepository, never()).findByPortfolioFiltered(anyLong(), any(), any());
    }

    // ---- DELETE: the customer's "remove" -------------------------------------------------

    @Test
    void shouldDeleteTransactionWith204() throws Exception {
        mockMvc.perform(delete("/api/v1/portfolios/7/transactions/91"))
                .andExpect(status().isNoContent());

        verify(transactionService).delete(USER.id(), PORTFOLIO_ID, 91L);
    }

    @Test
    void shouldReturn404WhenDeletingAnUnknownTransaction() throws Exception {
        doThrow(new NotFoundException("transaction", 999L))
                .when(transactionService).delete(USER.id(), PORTFOLIO_ID, 999L);

        mockMvc.perform(delete("/api/v1/portfolios/7/transactions/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/transaction-not-found"));
    }

    @Test
    void shouldNotDeleteOtherUsersTransactionThroughOwnPortfolioPath() throws Exception {
        // The row exists, but it belongs to another user's portfolio, so the delete is scoped
        // out by portfolio id and reports not-found rather than confirming it exists.
        doThrow(new NotFoundException("transaction", 555L))
                .when(transactionService).delete(USER.id(), PORTFOLIO_ID, 555L);

        mockMvc.perform(delete("/api/v1/portfolios/7/transactions/555"))
                .andExpect(status().isNotFound());

        verify(transactionService).delete(USER.id(), PORTFOLIO_ID, 555L);
    }
}
