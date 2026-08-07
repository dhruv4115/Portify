package com.protify.portfolio.transaction;

import com.protify.portfolio.common.error.CurrencyMismatchException;
import com.protify.portfolio.common.error.NotFoundException;
import com.protify.portfolio.common.money.MoneyUtils;
import com.protify.portfolio.holding.HoldingRepository;
import com.protify.portfolio.holding.HoldingState;
import com.protify.portfolio.holding.ProjectionContext;
import com.protify.portfolio.holding.ProjectionEngine;
import com.protify.portfolio.holding.ProjectionResult;
import com.protify.portfolio.instrument.Instrument;
import com.protify.portfolio.instrument.InstrumentRepository;
import com.protify.portfolio.portfolio.Portfolio;
import com.protify.portfolio.portfolio.PortfolioService;
import com.protify.portfolio.valuation.ValuationFolder;
import com.protify.portfolio.valuation.ValuationSnapshotService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The customer's "add" and "remove" (day-3-dev-A.md D3-A1/D3-A2). Each public method is one
 * atomic DB transaction — via an explicit {@link TransactionTemplate} rather than a
 * container-managed {@code @Transactional} proxy, so the same code path is exercisable from a
 * plain unit test (a mocked {@link PlatformTransactionManager} still runs the callback) and from
 * an integration test (a real one, built directly from the test datasource) without either
 * needing a full {@code ApplicationContext} — see {@code TransactionServiceIT}.
 *
 * <p>The projection is never persisted piecemeal: {@link #record} upserts only the one
 * instrument a BUY/SELL touches (every other holding is provably unchanged by a single new
 * transaction), while {@link #delete} clears and replays the whole portfolio, because a
 * mid-history delete can shift every subsequent holding (CLAUDE.md non-negotiable #12).
 */
@Service
public class TransactionService {

    private final PortfolioService portfolioService;
    private final InstrumentRepository instrumentRepository;
    private final TransactionRepository transactionRepository;
    private final HoldingRepository holdingRepository;
    private final ValuationSnapshotService snapshotService;
    private final TransactionTemplate transactionTemplate;

    public TransactionService(
            PortfolioService portfolioService,
            InstrumentRepository instrumentRepository,
            TransactionRepository transactionRepository,
            HoldingRepository holdingRepository,
            ValuationSnapshotService snapshotService,
            PlatformTransactionManager transactionManager) {
        this.portfolioService = portfolioService;
        this.instrumentRepository = instrumentRepository;
        this.transactionRepository = transactionRepository;
        this.holdingRepository = holdingRepository;
        this.snapshotService = snapshotService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public RecordTransactionResult record(long userId, long portfolioId, RecordTransactionCommand command) {
        TransactionRules.validate(command);
        return transactionTemplate.execute(status -> doRecord(userId, portfolioId, command));
    }

    private RecordTransactionResult doRecord(long userId, long portfolioId, RecordTransactionCommand command) {
        Portfolio portfolio = portfolioService.lockForUpdate(userId, portfolioId);

        Instrument instrument = resolveAndValidateInstrument(command);

        Txn candidate = new Txn(null, portfolioId, instrument == null ? null : instrument.id(),
                command.txnType(), command.quantity(), command.price(), command.fees(),
                command.currency(), command.executedAt(), command.note(), null);

        List<Txn> history = new ArrayList<>(transactionRepository.findByPortfolioOrderByExecutedAt(portfolioId));
        history.add(candidate);

        // Native run: throws InsufficientQuantityException here, before anything is written.
        ProjectionResult projected = ProjectionEngine.project(history, ProjectionContext.identity(portfolio.baseCurrency()));

        long txnId = transactionRepository.insert(candidate);

        // Every valuation from this transaction's date onward is now wrong (day-5-dev-A.md
        // D5-A2). Inside the same DB transaction as the write, so the cache cannot end up
        // emptied by a write that then rolled back — the same rule that binds the holdings
        // projection to the transaction that caused it (CLAUDE.md non-negotiable #12).
        snapshotService.invalidateFrom(portfolioId, ValuationFolder.dateOf(candidate));

        if (TransactionRules.AFFECTS_HOLDING.contains(command.txnType())) {
            // validate() already rejected a BUY/SELL with no symbol, so instrument is resolved.
            long instrumentId = Objects.requireNonNull(instrument, "instrument").id();
            HoldingState state = projected.holdingsByInstrument().get(instrumentId);
            holdingRepository.upsert(portfolioId, state);
        }

        List<String> warnings = new ArrayList<>();
        if (MoneyUtils.isNegative(projected.cashBalance())) {
            warnings.add("This transaction leaves the portfolio's cash balance negative.");
        }
        return new RecordTransactionResult(txnId, warnings);
    }

    public void delete(long userId, long portfolioId, long txnId) {
        transactionTemplate.executeWithoutResult(status -> doDelete(userId, portfolioId, txnId));
    }

    private void doDelete(long userId, long portfolioId, long txnId) {
        Portfolio portfolio = portfolioService.lockForUpdate(userId, portfolioId);

        // Read before the delete: the row's date decides how far back the valuation cache has to
        // be invalidated, and it is gone by the time the delete returns. This read is also what
        // now answers "no such transaction in this portfolio" — the row cannot disappear between
        // here and the delete below, because every write to this portfolio serialises behind the
        // SELECT ... FOR UPDATE above (ADR-0002).
        Txn doomed = transactionRepository.findByIdAndPortfolio(txnId, portfolioId)
                .orElseThrow(() -> new NotFoundException("transaction", txnId));

        transactionRepository.deleteByIdAndPortfolio(txnId, portfolioId);
        snapshotService.invalidateFrom(portfolioId, ValuationFolder.dateOf(doomed));

        List<Txn> remaining = transactionRepository.findByPortfolioOrderByExecutedAt(portfolioId);
        // A later SELL that's lost its cover throws here — the delete and any upsert below
        // roll back together with it (CLAUDE.md non-negotiable #12).
        ProjectionResult rebuilt = ProjectionEngine.project(remaining, ProjectionContext.identity(portfolio.baseCurrency()));

        holdingRepository.deleteAllForPortfolio(portfolioId);
        for (HoldingState state : rebuilt.holdingsByInstrument().values()) {
            holdingRepository.upsert(portfolioId, state);
        }
    }

    private Instrument resolveAndValidateInstrument(RecordTransactionCommand command) {
        if (command.symbol() == null) {
            return null;
        }
        Instrument instrument = instrumentRepository.findBySymbol(command.symbol())
                .orElseThrow(() -> new NotFoundException("instrument", command.symbol()));
        if (instrument.currency() != command.currency()) {
            throw new CurrencyMismatchException(instrument.currency(), command.currency());
        }
        return instrument;
    }

}
