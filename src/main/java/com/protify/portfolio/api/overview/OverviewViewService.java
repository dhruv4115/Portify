package com.protify.portfolio.api.overview;

import com.protify.portfolio.api.dto.CurrencySubtotalDto;
import com.protify.portfolio.api.dto.DataQualityDto;
import com.protify.portfolio.api.dto.MoneyDto;
import com.protify.portfolio.api.dto.OverviewResponse;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.money.Money;
import com.protify.portfolio.common.money.MoneyUtils;
import com.protify.portfolio.fx.CachingFxRateService;
import com.protify.portfolio.fx.ConversionResult;
import com.protify.portfolio.portfolio.Portfolio;
import com.protify.portfolio.portfolio.PortfolioService;
import com.protify.portfolio.valuation.ValuationResult;
import com.protify.portfolio.valuation.ValuationService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The account-level aggregation behind {@code GET /overview}.
 *
 * <p>Each portfolio is valued in its <b>own</b> base currency first — the figure that needs no
 * rate to be correct — and those are summed per currency. Only then is each per-currency
 * subtotal converted into the display currency, once, and those converted subtotals added.
 *
 * <p>Converting the subtotal rather than each portfolio is deliberate and not just cheaper:
 * conversion is linear, so the answer is the same, but it rounds once per currency instead of
 * once per portfolio, and it makes "this currency had no rate today" a single fact about a
 * currency rather than something rediscovered per portfolio.
 *
 * <p>A currency that cannot be converted is <b>excluded from the grand total and named in the
 * response</b> ({@code unconvertedCurrencies}, {@code convertedPortfolioCount}). It is never
 * folded in at a guessed rate and never silently dropped: a total that quietly omits a
 * portfolio is worse than one that says which portfolios it left out.
 */
@Service
public class OverviewViewService {

    /** Only used for an account with no portfolios at all, where there is no base currency to
     * infer one from and nothing to convert either way. */
    private static final CurrencyCode FALLBACK_CURRENCY = CurrencyCode.USD;

    private final PortfolioService portfolioService;
    private final ValuationService valuationService;
    private final CachingFxRateService fxRateService;

    public OverviewViewService(
            PortfolioService portfolioService,
            ValuationService valuationService,
            CachingFxRateService fxRateService) {
        this.portfolioService = portfolioService;
        this.valuationService = valuationService;
        this.fxRateService = fxRateService;
    }

    /**
     * @param requestedCurrency display currency for the grand total; {@code null} means "pick
     *                          the one most of this user's portfolios already use", which is
     *                          the choice least likely to need an FX rate at all
     */
    public OverviewResponse overview(long userId, CurrencyCode requestedCurrency) {
        LocalDate asOf = LocalDate.now(ZoneOffset.UTC);
        List<Portfolio> portfolios = portfolioService.findAllByUser(userId);

        if (portfolios.isEmpty()) {
            // Never a 404 — an account with no portfolios is a normal answer, same rule as
            // GET /portfolios.
            return empty(requestedCurrency != null ? requestedCurrency : FALLBACK_CURRENCY, asOf);
        }

        Map<CurrencyCode, Accumulator> byCurrency = new LinkedHashMap<>();
        int holdingCount = 0;
        LocalDate priceAsOf = null;
        LocalDate rateAsOf = null;

        for (Portfolio portfolio : portfolios) {
            ValuationResult result = valuationService.valuate(
                    userId, portfolio.id(), asOf, portfolio.baseCurrency());
            byCurrency.computeIfAbsent(portfolio.baseCurrency(), Accumulator::new).add(result);
            holdingCount += result.holdingCount();
            priceAsOf = earliest(priceAsOf, result.priceAsOf());
            rateAsOf = earliest(rateAsOf, result.rateAsOf());
        }

        CurrencyCode display = requestedCurrency != null
                ? requestedCurrency
                : dominantCurrency(byCurrency);

        Accumulator total = new Accumulator(display);
        List<CurrencyCode> unconverted = new ArrayList<>();
        int convertedPortfolios = 0;

        for (Accumulator subtotal : byCurrency.values()) {
            Optional<Accumulator> converted = subtotal.convertTo(display, asOf, fxRateService);
            if (converted.isEmpty()) {
                unconverted.add(subtotal.currency);
                continue;
            }
            total.add(converted.get());
            convertedPortfolios += subtotal.portfolioCount;
            rateAsOf = earliest(rateAsOf, converted.get().rateAsOf);
        }

        List<CurrencySubtotalDto> subtotals = byCurrency.values().stream()
                .sorted(Comparator.comparing(a -> a.currency))
                .map(Accumulator::toDto)
                .toList();

        return new OverviewResponse(
                display,
                portfolios.size(),
                holdingCount,
                MoneyDto.of(total.marketValue, display),
                MoneyDto.of(total.costBasis, display),
                MoneyDto.of(total.cashBalance, display),
                MoneyDto.of(total.totalValue, display),
                MoneyDto.of(total.unrealisedPnl, display),
                pct(total.costBasis, total.marketValue),
                MoneyDto.of(total.realisedPnl, display),
                subtotals,
                convertedPortfolios,
                List.copyOf(unconverted),
                asOf,
                DataQualityDto.of(priceAsOf, rateAsOf, asOf));
    }

    private static OverviewResponse empty(CurrencyCode display, LocalDate asOf) {
        return new OverviewResponse(
                display, 0, 0,
                MoneyDto.zero(display), MoneyDto.zero(display), MoneyDto.zero(display),
                MoneyDto.zero(display), MoneyDto.zero(display), null, MoneyDto.zero(display),
                List.of(), 0, List.of(), asOf,
                // Nothing was priced because there was nothing to price. That is not staleness.
                DataQualityDto.empty());
    }

    /**
     * The base currency shared by the most portfolios, ties broken by enum order so the same
     * account always gets the same answer. Choosing the majority currency means the common case
     * — every portfolio already in one currency — needs no FX rate at all, and the grand total
     * is then exactly the per-currency subtotal rather than a round-trip through a rate.
     */
    private static CurrencyCode dominantCurrency(Map<CurrencyCode, Accumulator> byCurrency) {
        return byCurrency.values().stream()
                .max(Comparator.comparingInt((Accumulator a) -> a.portfolioCount)
                        .thenComparing(a -> a.currency, Comparator.reverseOrder()))
                .map(a -> a.currency)
                .orElse(FALLBACK_CURRENCY);
    }

    /** The oldest date seen, treating {@code null} as "older than anything" — an unresolvable
     * date is the most stale a figure can be, which is exactly {@link DataQualityDto}'s rule. */
    private static LocalDate earliest(LocalDate current, LocalDate candidate) {
        if (current == null || candidate == null) {
            return null;
        }
        return candidate.isBefore(current) ? candidate : current;
    }

    private static String pct(BigDecimal costBasis, BigDecimal marketValue) {
        if (MoneyUtils.isZero(costBasis)) {
            // Never Infinity and never a misleading 0 — §4.2's rule, same as every other
            // percentage in this API.
            return null;
        }
        return MoneyUtils.pctChange(costBasis, marketValue).toPlainString();
    }

    private static BigDecimal nz(BigDecimal value) {
        return Objects.requireNonNullElse(value, BigDecimal.ZERO);
    }

    /**
     * A running total for one currency. Mutable and package-private on purpose: it exists for
     * the length of one request and never escapes this class except through {@link #toDto}.
     */
    private static final class Accumulator {

        private final CurrencyCode currency;
        private int portfolioCount;
        private BigDecimal marketValue = BigDecimal.ZERO;
        private BigDecimal costBasis = BigDecimal.ZERO;
        private BigDecimal cashBalance = BigDecimal.ZERO;
        private BigDecimal totalValue = BigDecimal.ZERO;
        private BigDecimal unrealisedPnl = BigDecimal.ZERO;
        private BigDecimal realisedPnl = BigDecimal.ZERO;
        private LocalDate rateAsOf;

        Accumulator(CurrencyCode currency) {
            this.currency = currency;
        }

        /**
         * A {@code null} money field means the portfolio holds positions none of which could be
         * priced. It contributes zero here rather than blanking the whole account's total — and
         * it does not go unmentioned, because that same portfolio's {@code priceAsOf} is
         * {@code null} too, which is what drives {@code dataQuality.stale} true in the response.
         */
        void add(ValuationResult result) {
            portfolioCount++;
            marketValue = marketValue.add(nz(result.marketValue()));
            costBasis = costBasis.add(nz(result.costBasis()));
            cashBalance = cashBalance.add(nz(result.cashBalance()));
            totalValue = totalValue.add(nz(result.totalValue()));
            unrealisedPnl = unrealisedPnl.add(nz(result.unrealisedPnl()));
            realisedPnl = realisedPnl.add(nz(result.realisedPnl()));
        }

        void add(Accumulator other) {
            portfolioCount += other.portfolioCount;
            marketValue = marketValue.add(other.marketValue);
            costBasis = costBasis.add(other.costBasis);
            cashBalance = cashBalance.add(other.cashBalance);
            totalValue = totalValue.add(other.totalValue);
            unrealisedPnl = unrealisedPnl.add(other.unrealisedPnl);
            realisedPnl = realisedPnl.add(other.realisedPnl);
        }

        /**
         * This subtotal expressed in {@code target}, or empty when no rate resolves — in which
         * case the caller excludes it from the grand total and names the currency, rather than
         * substituting a rate of its own.
         *
         * <p>Same-currency short-circuits without touching the FX service: a portfolio already
         * in the display currency must never be excluded merely because nobody stores a
         * self-rate row.
         */
        Optional<Accumulator> convertTo(CurrencyCode target, LocalDate on, CachingFxRateService fx) {
            if (currency == target) {
                return Optional.of(this);
            }

            Accumulator out = new Accumulator(target);
            out.portfolioCount = portfolioCount;

            BigDecimal[] sources = {marketValue, costBasis, cashBalance, totalValue, unrealisedPnl, realisedPnl};
            BigDecimal[] converted = new BigDecimal[sources.length];
            for (int i = 0; i < sources.length; i++) {
                Optional<ConversionResult> result = fx.convert(new Money(sources[i], currency), target, on);
                if (result.isEmpty()) {
                    return Optional.empty();
                }
                converted[i] = result.get().amount();
                out.rateAsOf = result.get().asOf();
            }

            out.marketValue = converted[0];
            out.costBasis = converted[1];
            out.cashBalance = converted[2];
            out.totalValue = converted[3];
            out.unrealisedPnl = converted[4];
            out.realisedPnl = converted[5];
            return Optional.of(out);
        }

        CurrencySubtotalDto toDto() {
            return new CurrencySubtotalDto(
                    currency,
                    portfolioCount,
                    MoneyDto.of(marketValue, currency),
                    MoneyDto.of(costBasis, currency),
                    MoneyDto.of(cashBalance, currency),
                    MoneyDto.of(totalValue, currency),
                    MoneyDto.of(unrealisedPnl, currency),
                    pct(costBasis, marketValue));
        }
    }
}
