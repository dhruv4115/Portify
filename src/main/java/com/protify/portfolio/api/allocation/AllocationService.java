package com.protify.portfolio.api.allocation;

import com.protify.portfolio.api.dto.AllocationDimension;
import com.protify.portfolio.api.dto.AllocationResponse;
import com.protify.portfolio.api.dto.AllocationSliceDto;
import com.protify.portfolio.api.dto.DataQualityDto;
import com.protify.portfolio.api.dto.HoldingResponse;
import com.protify.portfolio.api.dto.InstrumentResponse;
import com.protify.portfolio.api.dto.MoneyDto;
import com.protify.portfolio.api.holding.HoldingViewService;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.money.MoneyUtils;
import com.protify.portfolio.portfolio.Portfolio;
import com.protify.portfolio.portfolio.PortfolioService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * API_CONTRACT.md §13 — built from {@link HoldingViewService}'s already-priced holdings rather
 * than as a new method on Dev A's {@code valuation/} package (not built as of Day 4; this
 * mirrors the {@code PortfolioSummaryProvider} seam from Day 2). Cash is excluded from the
 * total, per the contract, and reported separately (it already is, via {@code /valuation}).
 */
@Service
public class AllocationService {

    private static final Map<CurrencyCode, String> CURRENCY_LABELS = Map.of(
            CurrencyCode.USD, "US Dollar",
            CurrencyCode.EUR, "Euro",
            CurrencyCode.GBP, "Pound Sterling",
            CurrencyCode.INR, "Indian Rupee");

    private final PortfolioService portfolioService;
    private final HoldingViewService holdingViewService;

    public AllocationService(PortfolioService portfolioService, HoldingViewService holdingViewService) {
        this.portfolioService = portfolioService;
        this.holdingViewService = holdingViewService;
    }

    /** The key of the slice the tail is folded into when {@code limit} is applied (§13). Fixed,
     * not derived from data, so a client can style and label it without pattern-matching. */
    public static final String OTHER_KEY = "OTHER";

    public AllocationResponse compute(long userId, long portfolioId, AllocationDimension by,
            CurrencyCode requestedCurrency) {
        return compute(userId, portfolioId, by, requestedCurrency, null);
    }

    /**
     * @param limit maximum slices to return, or {@code null} for all of them. Beyond this the
     *              smallest are folded into one {@code OTHER} slice, so the caller gets
     *              {@code limit} slices exactly.
     */
    public AllocationResponse compute(long userId, long portfolioId, AllocationDimension by,
            CurrencyCode requestedCurrency, Integer limit) {
        Portfolio portfolio = portfolioService.getOrThrow(userId, portfolioId);
        CurrencyCode currency = requestedCurrency != null ? requestedCurrency : portfolio.baseCurrency();

        List<HoldingResponse> holdings = holdingViewService.list(userId, portfolioId, currency, false);

        // One unpriceable position must not blank the whole allocation, same rule as everywhere
        // else market value is aggregated (TEST_PLAN.md §4.3) — it just cannot be sliced.
        List<HoldingResponse> priced = holdings.stream().filter(h -> h.marketValue() != null).toList();

        BigDecimal total = priced.stream()
                .map(h -> new BigDecimal(h.marketValue().amount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, List<HoldingResponse>> grouped = new LinkedHashMap<>();
        for (HoldingResponse holding : priced) {
            grouped.computeIfAbsent(keyFor(holding, by), k -> new ArrayList<>()).add(holding);
        }

        List<AllocationSliceDto> slices = fold(buildSlices(grouped, by, total, currency), limit, currency);

        boolean anyStale = holdings.stream().anyMatch(h -> h.dataQuality().stale());
        LocalDate priceAsOf = holdings.stream()
                .map(h -> h.dataQuality().priceAsOf())
                .filter(java.util.Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(null);
        LocalDate rateAsOf = holdings.stream()
                .map(h -> h.dataQuality().rateAsOf())
                .filter(java.util.Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(null);

        return new AllocationResponse(portfolioId, by, currency, MoneyDto.of(total, currency), slices,
                new DataQualityDto(priceAsOf, rateAsOf, anyStale || holdings.size() != priced.size()));
    }

    private List<AllocationSliceDto> buildSlices(
            Map<String, List<HoldingResponse>> grouped, AllocationDimension by, BigDecimal total, CurrencyCode currency) {
        List<AllocationSliceDto> slices = new ArrayList<>();
        BigDecimal weightSum = BigDecimal.ZERO;
        String largestKey = null;
        BigDecimal largestValue = null;

        for (var entry : grouped.entrySet()) {
            BigDecimal sliceValue = entry.getValue().stream()
                    .map(h -> new BigDecimal(h.marketValue().amount()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal weightPct = MoneyUtils.safeDivide(sliceValue.multiply(BigDecimal.valueOf(100)), total, 4);
            if (weightPct == null) {
                weightPct = BigDecimal.ZERO.setScale(4);
            }
            weightSum = weightSum.add(weightPct);

            if (largestValue == null || sliceValue.compareTo(largestValue) > 0) {
                largestValue = sliceValue;
                largestKey = entry.getKey();
            }

            slices.add(new AllocationSliceDto(
                    entry.getKey(), labelFor(entry.getKey(), entry.getValue().get(0), by),
                    MoneyDto.of(sliceValue, currency), weightPct.toPlainString(), entry.getValue().size()));
        }

        // Residual rounding lands on the largest slice, so weights sum to exactly 100.0000
        // whenever there is anything to allocate at all.
        if (largestKey != null) {
            BigDecimal residual = BigDecimal.valueOf(100).subtract(weightSum);
            for (int i = 0; i < slices.size(); i++) {
                AllocationSliceDto slice = slices.get(i);
                if (slice.key().equals(largestKey)) {
                    BigDecimal corrected = new BigDecimal(slice.weightPct()).add(residual).setScale(4, MoneyUtils.ROUNDING);
                    slices.set(i, new AllocationSliceDto(
                            slice.key(), slice.label(), slice.value(), corrected.toPlainString(), slice.instrumentCount()));
                    break;
                }
            }
        }

        // Largest first. The map is in holdings order, which is arbitrary as far as a reader is
        // concerned — a pie chart's wedges and its legend both mean "biggest to smallest", and
        // `limit` below could not mean "the top ones" against any other order.
        slices.sort(Comparator
                .<AllocationSliceDto, BigDecimal>comparing(slice -> new BigDecimal(slice.value().amount()))
                .reversed()
                .thenComparing(AllocationSliceDto::key));

        return slices;
    }

    /**
     * Folds everything past {@code limit} into one {@code OTHER} slice (API_CONTRACT.md §13).
     *
     * <p>This happens on the server because it is a sum of money. Every amount on the wire is a
     * decimal string precisely so a client never adds them as IEEE-754 doubles (§0.2); a client
     * folding its own "other" bucket would be doing exactly that, and the total under the chart
     * would drift from the total the rest of the product reports.
     *
     * <p>Weights are summed from the already-corrected percentages rather than recomputed from
     * the folded value, so the residual that was placed on the largest slice survives folding
     * and the result still sums to exactly 100.
     *
     * <p>Folding one slice into an "Other" of one is not folding — {@code limit} is honoured only
     * when there is genuinely a tail, so a request for 8 against 8 slices returns those 8 named
     * rather than 7 and a bucket.
     */
    private static List<AllocationSliceDto> fold(List<AllocationSliceDto> slices, Integer limit,
            CurrencyCode currency) {
        if (limit == null || slices.size() <= limit) {
            return slices;
        }

        List<AllocationSliceDto> kept = new ArrayList<>(slices.subList(0, limit - 1));
        List<AllocationSliceDto> tail = slices.subList(limit - 1, slices.size());

        BigDecimal value = BigDecimal.ZERO;
        BigDecimal weightPct = BigDecimal.ZERO;
        int instrumentCount = 0;
        for (AllocationSliceDto slice : tail) {
            value = value.add(new BigDecimal(slice.value().amount()));
            weightPct = weightPct.add(new BigDecimal(slice.weightPct()));
            instrumentCount += slice.instrumentCount();
        }

        kept.add(new AllocationSliceDto(OTHER_KEY, "Other", MoneyDto.of(value, currency),
                weightPct.setScale(4, MoneyUtils.ROUNDING).toPlainString(), instrumentCount));
        return List.copyOf(kept);
    }

    private static String keyFor(HoldingResponse holding, AllocationDimension by) {
        InstrumentResponse instrument = holding.instrument();
        return switch (by) {
            case ASSET_TYPE -> instrument.assetType().name();
            case SECTOR -> instrument.sector() == null ? "UNKNOWN" : instrument.sector();
            case CURRENCY -> instrument.currency().name();
            case INSTRUMENT -> instrument.symbol();
        };
    }

    private static String labelFor(String key, HoldingResponse sample, AllocationDimension by) {
        InstrumentResponse instrument = sample.instrument();
        return switch (by) {
            case ASSET_TYPE -> instrument.assetType().name();
            case SECTOR -> key;
            case CURRENCY -> CURRENCY_LABELS.getOrDefault(instrument.currency(), key);
            case INSTRUMENT -> instrument.name();
        };
    }
}
