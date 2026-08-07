package com.protify.portfolio.transaction;

import com.protify.portfolio.common.error.DomainException;
import com.protify.portfolio.common.error.InsufficientQuantityException;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bulk "add", for importing a ledger rather than typing it (API_CONTRACT.md §8.1). The counterpart
 * to {@link TransactionService#record}, and deliberately <em>not</em> a loop over it.
 *
 * <p>Calling {@code record} n times would be correct and quadratic: each call re-locks the
 * portfolio, re-reads the whole history and re-folds it, so importing a thousand transactions
 * would read half a million rows and hold the row lock for all of it. This does the same work
 * once — lock, read history, fold history + every candidate, write — which is also what makes the
 * batch's rules right rather than merely fast: a file whose BUY on line 4 covers its SELL on line
 * 9 is a valid file, and only a fold that sees both at once can say so.
 *
 * <p><b>All or nothing.</b> Everything below happens in one database transaction, so a file that
 * fails anywhere writes nothing at all. A half-applied import is worse than a rejected one: the
 * user cannot tell which half, and re-uploading the corrected file would duplicate whatever did
 * land. On failure they fix the line the summary names and upload the same file again.
 *
 * <p>Holdings are rebuilt for the whole portfolio rather than upserted per instrument, following
 * {@link TransactionService#delete}'s reasoning: an import is history inserted in the
 * <em>middle</em> of the ledger, not appended to the end, and a back-dated BUY shifts the average
 * cost of every later transaction in that instrument (CLAUDE.md non-negotiable #12).
 */
@Service
public class TransactionImportService {

    private final PortfolioService portfolioService;
    private final InstrumentRepository instrumentRepository;
    private final TransactionRepository transactionRepository;
    private final HoldingRepository holdingRepository;
    private final ValuationSnapshotService snapshotService;
    private final TransactionTemplate transactionTemplate;

    public TransactionImportService(
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

    /** What an import did, or — on a dry run — what it would have done. */
    public record ImportResult(int imported, List<String> warnings) {
        public ImportResult {
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * @param dryRun validate and project exactly as a real import would, then roll back. It is the
     *               same code path, not a cheaper approximation of it — a preview that took a
     *               different route could pass while the real thing failed, which is the one thing
     *               a preview must never do.
     * @throws RowRejectedException when one transaction in the batch is not acceptable, naming its
     *               index. Nothing is written.
     * @throws NotFoundException when the portfolio is not this user's — a 404, as everywhere else.
     */
    public ImportResult importAll(long userId, long portfolioId, List<RecordTransactionCommand> commands,
            boolean dryRun) {
        if (commands.isEmpty()) {
            return new ImportResult(0, List.of());
        }
        for (int i = 0; i < commands.size(); i++) {
            try {
                TransactionRules.validate(commands.get(i));
            } catch (DomainException ex) {
                throw new RowRejectedException(i, ex.getMessage());
            }
        }
        return transactionTemplate.execute(status -> {
            ImportResult result = doImport(userId, portfolioId, commands);
            if (dryRun) {
                status.setRollbackOnly();
            }
            return result;
        });
    }

    private ImportResult doImport(long userId, long portfolioId, List<RecordTransactionCommand> commands) {
        Portfolio portfolio = portfolioService.lockForUpdate(userId, portfolioId);

        List<Txn> candidates = toCandidates(portfolioId, commands);
        List<Txn> history = new ArrayList<>(transactionRepository.findByPortfolioOrderByExecutedAt(portfolioId));

        List<Txn> combined = new ArrayList<>(history);
        combined.addAll(candidates);

        // Throws before anything is written. On failure the batch is re-folded one row at a time
        // to find out *which* row broke it — see attribute().
        ProjectionResult projected = project(portfolio, combined, history, candidates);

        for (Txn candidate : candidates) {
            transactionRepository.insert(candidate);
        }

        // Every cached valuation from the earliest imported date onward is now wrong. Inside the
        // same DB transaction as the writes, so a rollback cannot leave the cache emptied for a
        // history that was never actually changed (day-5-dev-A.md D5-A2).
        snapshotService.invalidateFrom(portfolioId, earliestDate(candidates));

        holdingRepository.deleteAllForPortfolio(portfolioId);
        for (HoldingState state : projected.holdingsByInstrument().values()) {
            holdingRepository.upsert(portfolioId, state);
        }

        List<String> warnings = new ArrayList<>();
        if (MoneyUtils.isNegative(projected.cashBalance())) {
            warnings.add("These transactions leave the portfolio's cash balance negative.");
        }
        return new ImportResult(candidates.size(), warnings);
    }

    /**
     * Resolves each command's symbol to an instrument and builds the row that will be inserted.
     * The two failures possible here — an unknown symbol and a currency that is not the
     * instrument's — are the same two {@link TransactionService#record} raises, re-thrown against
     * the row that caused them so the summary can name a line instead of a symbol.
     */
    private List<Txn> toCandidates(long portfolioId, List<RecordTransactionCommand> commands) {
        Map<String, Instrument> resolved = new HashMap<>();
        List<Txn> candidates = new ArrayList<>(commands.size());

        for (int i = 0; i < commands.size(); i++) {
            RecordTransactionCommand command = commands.get(i);
            Instrument instrument = null;
            if (command.symbol() != null) {
                // One lookup per distinct symbol, not one per row: a year of trading is typically
                // hundreds of rows over a handful of instruments.
                instrument = resolved.computeIfAbsent(command.symbol(),
                        symbol -> instrumentRepository.findBySymbol(symbol).orElse(null));
                if (instrument == null) {
                    throw new RowRejectedException(i, "No instrument found with symbol \"%s\"."
                            .formatted(command.symbol()));
                }
                if (instrument.currency() != command.currency()) {
                    throw new RowRejectedException(i, "%s trades in %s, but the row says %s."
                            .formatted(instrument.symbol(), instrument.currency(), command.currency()));
                }
            }
            candidates.add(new Txn(null, portfolioId, instrument == null ? null : instrument.id(),
                    command.txnType(), command.quantity(), command.price(), command.fees(),
                    command.currency(), command.executedAt(), command.note(), null));
        }
        return candidates;
    }

    private ProjectionResult project(Portfolio portfolio, List<Txn> combined, List<Txn> history,
            List<Txn> candidates) {
        ProjectionContext context = ProjectionContext.identity(portfolio.baseCurrency());
        try {
            return ProjectionEngine.project(combined, context);
        } catch (InsufficientQuantityException ex) {
            throw new RowRejectedException(attribute(history, candidates, context), message(ex));
        }
    }

    /**
     * Which imported row made the fold fail.
     *
     * <p>{@link ProjectionEngine} reports the transaction it choked on, but it folds a
     * <em>sorted</em> list, so that transaction's position there says nothing about its line in
     * the file — and an over-sold position is as often caused by a missing BUY as by the SELL that
     * finally exposed it. Re-folding with one more candidate each time finds the first row the
     * ledger cannot absorb, which is the row worth pointing at. Quadratic, and only ever reached
     * on the failure path, where a few milliseconds buy a message that names a line.
     */
    private static int attribute(List<Txn> history, List<Txn> candidates, ProjectionContext context) {
        List<Txn> growing = new ArrayList<>(history);
        for (int i = 0; i < candidates.size(); i++) {
            growing.add(candidates.get(i));
            try {
                ProjectionEngine.project(growing, context);
            } catch (InsufficientQuantityException ex) {
                return i;
            }
        }
        // Unreachable in practice: the full list threw, so some prefix of it must too. Blaming the
        // last row is still better than blaming none.
        return candidates.size() - 1;
    }

    /**
     * {@link ProjectionEngine} identifies the instrument by id, because it is a pure fold with no
     * repository to ask. The symbol is what the user wrote in the file, so it is put back here.
     *
     * <p>The sentence is <em>rebuilt</em> from the exception's own numbers rather than patched.
     * Substituting the symbol into the rendered message with {@code replace} corrupts it: the id
     * is a bare number, so replacing {@code "5"} with {@code "JPM"} also rewrites the digits inside
     * the quantities, turning "Cannot sell 500 of 5; holding is 15." into "Cannot sell JPM00 of
     * JPM; holding is 1JPM." Reusing the exception's constructor keeps one definition of how that
     * sentence reads, and no string surgery at all.
     */
    private String message(InsufficientQuantityException ex) {
        String symbol = parseInstrumentId(ex.symbol())
                .flatMap(instrumentRepository::findById)
                .map(Instrument::symbol)
                .orElse(null);
        return symbol == null
                ? ex.getMessage()
                : new InsufficientQuantityException(symbol, ex.requested(), ex.held()).getMessage();
    }

    private static Optional<Long> parseInstrumentId(String raw) {
        try {
            return Optional.of(Long.valueOf(raw));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private static LocalDate earliestDate(List<Txn> candidates) {
        return candidates.stream()
                .map(ValuationFolder::dateOf)
                .min(LocalDate::compareTo)
                .orElseThrow(() -> new IllegalStateException("an import with no rows should have returned earlier"));
    }
}
