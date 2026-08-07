package com.protify.portfolio.valuation.spi;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.money.Money;
import com.protify.portfolio.fx.CachingFxRateService;
import com.protify.portfolio.fx.FxRateRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The single, audited seam between the valuation layer and Dev B's {@code fx} package — the FX
 * twin of {@link MarketDataPriceLookupAdapter}, and exempt from
 * {@code ArchitectureTest#coreMustNotDependOnPlatform} for the same reason.
 *
 * <p>Translation only: the fallback chain (Caffeine → provider → last-good {@code fx_rate} row)
 * and the USD-pivot cross-rate arithmetic both stay inside {@link CachingFxRateService}.
 */
@Component
class FxRateConversionAdapter implements FxConversion {

    private final CachingFxRateService fxRateService;
    private final FxRateRepository fxRateRepository;

    FxRateConversionAdapter(CachingFxRateService fxRateService, FxRateRepository fxRateRepository) {
        this.fxRateService = fxRateService;
        this.fxRateRepository = fxRateRepository;
    }

    @Override
    public Optional<ConvertedMoney> convert(Money money, CurrencyCode target, LocalDate on) {
        return fxRateService.convert(money, target, on)
                .map(result -> new ConvertedMoney(result.amount(), result.currency(), result.asOf()));
    }

    @Override
    public Optional<BigDecimal> rate(CurrencyCode from, CurrencyCode to, LocalDate on) {
        return fxRateService.rate(from, to, on).map(quote -> quote.rate());
    }

    @Override
    public NavigableMap<LocalDate, Map<CurrencyCode, BigDecimal>> ratesUpTo(LocalDate to) {
        return fxRateRepository.findRange(to);
    }
}
