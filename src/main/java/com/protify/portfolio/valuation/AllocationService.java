package com.protify.portfolio.valuation;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.money.MoneyUtils;
import com.protify.portfolio.holding.ProjectionContext;
import com.protify.portfolio.instrument.Instrument;
import com.protify.portfolio.portfolio.Portfolio;
import com.protify.portfolio.portfolio.PortfolioService;
import com.protify.portfolio.transaction.Txn;
import com.protify.portfolio.transaction.TransactionRepository;
import com.protify.portfolio.valuation.spi.FxConversion;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * day-4-dev-A.md D4-A4 — {@code portfolios/{id}/allocation} (API_CONTRACT.md §13). Breaks the
 * portfolio down by asset type, sector, currency or instrument, all in the requested currency,
 * over the same {@link ValuationFolder} projection and the same {@link PositionPricer} that
 * {@link ValuationService} uses. Sharing both is the point: an allocation whose slices summed to
 * something other than the portfolio's own market value would be a bug visible in neither class
 * on its own.
 *
 * <p><b>Cash is excluded from the slices</b> and reported separately — it has no asset type,
 * sector or issuer, so a "cash" wedge would distort every other weight while answering nothing.
 *
 * <p><b>Weights sum to exactly 100.</b> Rounding four-decimal percentages independently leaves a
 * residual of a few ten-thousandths; it is added to the largest slice, where it is
 * proportionally smallest and least visible, rather than left to make the total read 99.9999.
 */
// Explicit bean name: api/allocation/AllocationService (merged from feature2/vaishnavi-d4)
// takes the default "allocationService", and two @Service classes with the same simple name
// break component scanning outright. See this class's javadoc for which one should survive.
@Service("valuationAllocationService")
public class AllocationService {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final String UNCLASSIFIED = "UNCLASSIFIED";

    private final PortfolioService portfolioService;
    private final TransactionRepository transactionRepository;
    private final PositionPricer positionPricer;
    private final FxConversion fxConversion;

    public AllocationService(
            PortfolioService portfolioService,
            TransactionRepository transactionRepository,
            PositionPricer positionPricer,
            FxConversion fxConversion) {
        this.portfolioService = portfolioService;
        this.transactionRepository = transactionRepository;
        this.positionPricer = positionPricer;
        this.fxConversion = fxConversion;
    }

    public AllocationResult allocate(long userId, long portfolioId, AllocateBy by, LocalDate asOf,
            CurrencyCode requestedCurrency) {
        // getOrThrow, not findById: another user's portfolio must 404, never 403 (§4.7).
        Portfolio portfolio = portfolioService.getOrThrow(userId, portfolioId);
        CurrencyCode currency = requestedCurrency != null ? requestedCurrency : portfolio.baseCurrency();

        List<Txn> upToAsOf = transactionRepository.findByPortfolioOrderByExecutedAt(portfolioId).stream()
                .filter(txn -> !ValuationFolder.dateOf(txn).isAfter(asOf))
                .toList();
        ValuationFolder.Snapshot snapshot = ValuationFolder.fold(upToAsOf, tradeDateFxLookup(currency));

        Map<String, Bucket> buckets = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        LocalDate priceAsOf = null;
        LocalDate rateAsOf = null;
        boolean stale = false;

        Map<Long, BigDecimal> openQuantities = new LinkedHashMap<>();
        for (Map.Entry<Long, ValuationFolder.InstrumentPosition> entry : snapshot.positions().entrySet()) {
            if (entry.getValue().quantity().signum() > 0) {
                openQuantities.put(entry.getKey(), entry.getValue().quantity());
            }
        }
        // One instrument query for the whole portfolio rather than one per slice (D5-A3).
        Map<Long, PositionPricer.Priced> pricedByInstrument = positionPricer.priceAll(openQuantities, currency, asOf);

        for (Long instrumentId : openQuantities.keySet()) {
            PositionPricer.Priced value = pricedByInstrument.get(instrumentId);
            if (value == null) {
                // One unpriceable holding degrades the result; it never blanks it (§4.3).
                stale = true;
                continue;
            }
            Instrument instrument = value.instrument();

            buckets.computeIfAbsent(keyOf(by, instrument), key -> new Bucket(labelOf(by, instrument)))
                    .add(value.marketValue());
            total = total.add(value.marketValue());

            if (!value.priceAsOf().equals(asOf) || !value.rateAsOf().equals(asOf)) {
                stale = true;
            }
            priceAsOf = earlierOf(priceAsOf, value.priceAsOf());
            rateAsOf = earlierOf(rateAsOf, value.rateAsOf());
        }

        BigDecimal roundedTotal = MoneyUtils.money(total);
        return new AllocationResult(portfolioId, by, currency, asOf, roundedTotal,
                snapshot.cashBalance(), toSlices(buckets, roundedTotal), priceAsOf, rateAsOf, stale);
    }

    /**
     * Weights, largest slice first, summing to exactly 100.
     *
     * <p>An empty portfolio returns no slices rather than dividing by zero — the guard is on
     * {@code total} being zero, not on the map being empty, because a portfolio whose every
     * holding priced at zero reaches here with buckets present and no total to apportion.
     */
    private List<AllocationSlice> toSlices(Map<String, Bucket> buckets, BigDecimal total) {
        if (buckets.isEmpty()) {
            return List.of();
        }

        List<Map.Entry<String, Bucket>> ordered = new ArrayList<>(buckets.entrySet());
        ordered.sort(Comparator.<Map.Entry<String, Bucket>, BigDecimal>comparing(e -> e.getValue().value)
                .reversed()
                .thenComparing(Map.Entry::getKey));

        boolean apportionable = total.signum() > 0;
        List<AllocationSlice> slices = new ArrayList<>();
        BigDecimal weightSum = BigDecimal.ZERO;
        for (Map.Entry<String, Bucket> entry : ordered) {
            Bucket bucket = entry.getValue();
            BigDecimal weight = apportionable
                    ? MoneyUtils.money(bucket.value.multiply(ONE_HUNDRED).divide(total, MoneyUtils.MONEY_SCALE + 4, MoneyUtils.ROUNDING))
                    : MoneyUtils.money(BigDecimal.ZERO);
            weightSum = weightSum.add(weight);
            slices.add(new AllocationSlice(entry.getKey(), bucket.label,
                    MoneyUtils.money(bucket.value), weight, bucket.instrumentCount));
        }

        if (apportionable) {
            // ordered is largest-first, so slot 0 is the largest slice.
            BigDecimal residual = MoneyUtils.money(ONE_HUNDRED).subtract(weightSum);
            AllocationSlice largest = slices.get(0);
            slices.set(0, new AllocationSlice(largest.key(), largest.label(), largest.value(),
                    largest.weightPct().add(residual), largest.instrumentCount()));
        }
        return List.copyOf(slices);
    }

    private static String keyOf(AllocateBy by, Instrument instrument) {
        return switch (by) {
            case ASSET_TYPE -> instrument.assetType().name();
            case SECTOR -> instrument.sector() == null || instrument.sector().isBlank()
                    ? UNCLASSIFIED : instrument.sector();
            case CURRENCY -> instrument.currency().name();
            case INSTRUMENT -> instrument.symbol();
        };
    }

    private static String labelOf(AllocateBy by, Instrument instrument) {
        return switch (by) {
            case ASSET_TYPE -> titleCase(instrument.assetType().name());
            case SECTOR -> instrument.sector() == null || instrument.sector().isBlank()
                    ? "Unclassified" : instrument.sector();
            // From the JDK's own currency table rather than a hard-coded map here — one fewer
            // thing to keep in step with CurrencyCode.
            case CURRENCY -> Currency.getInstance(instrument.currency().name()).getDisplayName(Locale.ENGLISH);
            case INSTRUMENT -> instrument.name();
        };
    }

    /** {@code MUTUAL_FUND} to {@code Mutual Fund} — enum names are not display text. */
    private static String titleCase(String enumName) {
        String[] words = enumName.split("_");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ENGLISH));
        }
        return out.toString();
    }

    private ProjectionContext.FxLookup tradeDateFxLookup(CurrencyCode target) {
        return (from, on) -> fxConversion.rate(from, target, on).orElse(BigDecimal.ONE);
    }

    private static LocalDate earlierOf(LocalDate a, LocalDate b) {
        if (a == null) {
            return b;
        }
        return a.isBefore(b) ? a : b;
    }

    /** A running total plus the number of distinct instruments that landed in it. */
    private static final class Bucket {
        private final String label;
        private BigDecimal value = BigDecimal.ZERO;
        private int instrumentCount;

        private Bucket(String label) {
            this.label = label;
        }

        private void add(BigDecimal marketValue) {
            value = value.add(marketValue);
            instrumentCount++;
        }
    }
}
