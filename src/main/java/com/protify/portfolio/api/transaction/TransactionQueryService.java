package com.protify.portfolio.api.transaction;

import com.protify.portfolio.api.dto.TransactionResponse;
import com.protify.portfolio.api.mapper.TransactionMapper;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.error.ValidationException;
import com.protify.portfolio.common.port.FxQuote;
import com.protify.portfolio.fx.CachingFxRateService;
import com.protify.portfolio.instrument.Instrument;
import com.protify.portfolio.instrument.InstrumentRepository;
import com.protify.portfolio.portfolio.Portfolio;
import com.protify.portfolio.portfolio.PortfolioService;
import com.protify.portfolio.support.Page;
import com.protify.portfolio.support.Pageable;
import com.protify.portfolio.transaction.RecordTransactionCommand;
import com.protify.portfolio.transaction.TransactionRepository;
import com.protify.portfolio.transaction.Txn;
import com.protify.portfolio.transaction.TxnFilter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The read half of {@code /portfolios/{id}/transactions} (day-3-dev-C.md D3-C1): everything
 * needed to turn a {@code txn} row into API_CONTRACT.md §7's response shape, which is more than
 * the row carries — the instrument, the portfolio's base currency, and the FX rate on the
 * transaction's own execution date.
 *
 * <p>Read-side only, and deliberately separate from Dev A's {@code TransactionService}, which
 * owns every write and the projection rebuild. It exists so the controller stays
 * validate-delegate-map: assembling a response across four collaborators is not something a
 * controller should be doing.
 *
 * <p>Every method takes {@code userId} and resolves the portfolio through
 * {@link PortfolioService#getOrThrow} before touching a transaction, so another user's portfolio
 * is a {@code NotFoundException} — 404, never 403 — and no transaction query can run unscoped.
 */
@Service
public class TransactionQueryService {

    private final PortfolioService portfolioService;
    private final TransactionRepository transactionRepository;
    private final InstrumentRepository instrumentRepository;
    private final CachingFxRateService fxRateService;
    private final TransactionMapper transactionMapper;

    public TransactionQueryService(
            PortfolioService portfolioService,
            TransactionRepository transactionRepository,
            InstrumentRepository instrumentRepository,
            CachingFxRateService fxRateService,
            TransactionMapper transactionMapper) {
        this.portfolioService = portfolioService;
        this.transactionRepository = transactionRepository;
        this.instrumentRepository = instrumentRepository;
        this.fxRateService = fxRateService;
        this.transactionMapper = transactionMapper;
    }

    public Page<TransactionResponse> list(long userId, long portfolioId, TxnFilter filter, Pageable pageable) {
        requireValidRange(filter);
        Portfolio portfolio = portfolioService.getOrThrow(userId, portfolioId);

        Page<Txn> page = transactionRepository.findByPortfolioFiltered(portfolioId, filter, pageable);
        Map<Long, Instrument> instruments = instrumentsFor(page.content());
        List<TransactionResponse> content = page.content().stream()
                .map(txn -> toResponse(txn, instruments.get(txn.instrumentId()), portfolio.baseCurrency(), List.of()))
                .toList();

        return new Page<>(content, page.page(), page.size(), page.totalElements());
    }

    /**
     * Renders the transaction a {@code POST} has just written, for its 201 body. Rebuilds the
     * {@link Txn} from the command plus the generated id rather than re-reading it: the row was
     * written from exactly these values inside a committed transaction, so a second SELECT would
     * return the same numbers at the cost of another round trip.
     */
    public TransactionResponse describe(long userId, long portfolioId, long txnId,
            RecordTransactionCommand command, List<String> warnings) {
        Portfolio portfolio = portfolioService.getOrThrow(userId, portfolioId);
        Instrument instrument = command.symbol() == null
                ? null
                : instrumentRepository.findBySymbol(command.symbol()).orElse(null);

        Txn txn = new Txn(txnId, portfolioId, instrument == null ? null : instrument.id(),
                command.txnType(), command.quantity(), command.price(), command.fees(),
                command.currency(), command.executedAt(), command.note(), null);

        return toResponse(txn, instrument, portfolio.baseCurrency(), warnings);
    }

    private TransactionResponse toResponse(Txn txn, Instrument instrument, CurrencyCode baseCurrency,
            List<String> warnings) {
        return transactionMapper.toResponse(txn, instrument, baseCurrency,
                tradeDateRate(txn, baseCurrency), warnings);
    }

    /**
     * The rate on {@code executedAt}, not today's (API_CONTRACT.md §7). {@code null} when no rate
     * for that date exists anywhere in the fallback chain — {@code totalBase} is then reported as
     * {@code null} rather than being quietly assumed to be 1:1, which would misstate a
     * cross-currency transaction by whatever the real rate was.
     */
    private BigDecimal tradeDateRate(Txn txn, CurrencyCode baseCurrency) {
        LocalDate executedOn = txn.executedAt().atZone(ZoneOffset.UTC).toLocalDate();
        return fxRateService.rate(txn.currency(), baseCurrency, executedOn)
                .map(FxQuote::rate)
                .orElse(null);
    }

    /** One lookup per distinct instrument on the page, not one per row. */
    private Map<Long, Instrument> instrumentsFor(List<Txn> txns) {
        Map<Long, Instrument> instruments = new HashMap<>();
        txns.stream()
                .map(Txn::instrumentId)
                .filter(Objects::nonNull)
                .distinct()
                .map(instrumentRepository::findById)
                .flatMap(Optional::stream)
                .forEach(instrument -> instruments.put(instrument.id(), instrument));
        return instruments;
    }

    private static void requireValidRange(TxnFilter filter) {
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
            throw new ValidationException("invalid-date-range", "from must not be after to.");
        }
    }
}
