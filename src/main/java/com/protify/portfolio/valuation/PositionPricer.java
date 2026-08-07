package com.protify.portfolio.valuation;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.money.Money;
import com.protify.portfolio.instrument.Instrument;
import com.protify.portfolio.instrument.InstrumentRepository;
import com.protify.portfolio.valuation.spi.ConvertedMoney;
import com.protify.portfolio.valuation.spi.FxConversion;
import com.protify.portfolio.valuation.spi.PriceLookup;
import com.protify.portfolio.valuation.spi.PricePoint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * "Value one open position in a target currency, as at a date" — the step {@link ValuationService}
 * and {@link AllocationService} both need, extracted so they cannot drift apart. A portfolio whose
 * allocation slices summed to a different number than its own valuation would be a bug nobody
 * could see in either class alone, because each would look right on its own terms.
 *
 * <p>Market value uses the FX rate on {@code asOf}; cost basis is accumulated separately by
 * {@link ValuationFolder} at each transaction's own trade date. That difference is the currency
 * P&L and it is deliberate (ADR-0011), which is why this class prices only and never touches
 * cost.
 *
 * <p>Returns empty rather than throwing when a position cannot be valued — an unknown instrument,
 * no price anywhere, or no resolvable rate. One unpriceable holding must degrade to
 * {@code stale: true}, never blank the portfolio or 500 the request (TEST_PLAN.md §4.3).
 */
@Component
class PositionPricer {

    /** {@code priceAsOf}/{@code rateAsOf} are the dates genuinely used, which may be older than
     * the date asked for — that is what makes staleness reportable rather than invisible. */
    record Priced(Instrument instrument, BigDecimal marketValue, LocalDate priceAsOf, LocalDate rateAsOf) {
    }

    private final InstrumentRepository instrumentRepository;
    private final PriceLookup priceLookup;
    private final FxConversion fxConversion;

    PositionPricer(InstrumentRepository instrumentRepository, PriceLookup priceLookup, FxConversion fxConversion) {
        this.instrumentRepository = instrumentRepository;
        this.priceLookup = priceLookup;
        this.fxConversion = fxConversion;
    }

    /**
     * Every open position in one pass — day-5-dev-A.md D5-A3's N+1 hunt.
     *
     * <p>The instrument catalogue is resolved with a single {@code IN} query instead of one
     * {@code findById} per position. Prices and rates stay one call each: both go through
     * {@link PriceLookup}/{@link FxConversion} into Dev B's Caffeine-backed services, and each of
     * those owns a fallback chain (cache → provider → stored row → seed). Batching past them
     * would mean reaching around the chain the demo's offline path depends on, which is exactly
     * what {@code valuation/spi} exists to prevent — so the round trip that was worth removing is
     * the one that had no cache in front of it.
     *
     * <p>An instrument that cannot be valued is absent from the result rather than mapped to
     * null: one unpriceable holding degrades the response to {@code stale}, it never blanks the
     * portfolio (TEST_PLAN.md §4.3). Iteration order follows {@code quantities}.
     */
    Map<Long, Priced> priceAll(Map<Long, BigDecimal> quantities, CurrencyCode target, LocalDate asOf) {
        Map<Long, Instrument> instruments = instrumentRepository.findAllByIds(quantities.keySet());
        Map<Long, Priced> priced = new LinkedHashMap<>();
        for (Map.Entry<Long, BigDecimal> entry : quantities.entrySet()) {
            Instrument instrument = instruments.get(entry.getKey());
            if (instrument == null) {
                continue;
            }
            price(instrument, entry.getValue(), target, asOf)
                    .ifPresent(value -> priced.put(entry.getKey(), value));
        }
        return priced;
    }

    Optional<Priced> price(long instrumentId, BigDecimal quantity, CurrencyCode target, LocalDate asOf) {
        return Optional.ofNullable(priceAll(Map.of(instrumentId, quantity), target, asOf).get(instrumentId));
    }

    private Optional<Priced> price(Instrument instrument, BigDecimal quantity, CurrencyCode target, LocalDate asOf) {
        Optional<PricePoint> price = priceLookup.priceOn(instrument.id(), asOf);
        if (price.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal nativeMarketValue = quantity.multiply(price.get().price());
        Optional<ConvertedMoney> converted =
                fxConversion.convert(new Money(nativeMarketValue, instrument.currency()), target, asOf);
        if (converted.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Priced(
                instrument, converted.get().amount(), price.get().asOf(), converted.get().asOf()));
    }
}
