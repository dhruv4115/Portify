package com.protify.portfolio.api.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.protify.portfolio.api.dto.AllocationDimension;
import com.protify.portfolio.api.dto.AllocationResponse;
import com.protify.portfolio.api.dto.AllocationSliceDto;
import com.protify.portfolio.api.dto.DataQualityDto;
import com.protify.portfolio.api.dto.HoldingResponse;
import com.protify.portfolio.api.dto.InstrumentResponse;
import com.protify.portfolio.api.dto.MoneyDto;
import com.protify.portfolio.api.holding.HoldingViewService;
import com.protify.portfolio.common.enums.AssetType;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.portfolio.Portfolio;
import com.protify.portfolio.portfolio.PortfolioService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AllocationServiceTest {

    private final PortfolioService portfolioService = org.mockito.Mockito.mock(PortfolioService.class);
    private final HoldingViewService holdingViewService = org.mockito.Mockito.mock(HoldingViewService.class);
    private final AllocationService service = new AllocationService(portfolioService, holdingViewService);

    private static final Portfolio PORTFOLIO = new Portfolio(
            7L, 42L, "Growth", CurrencyCode.INR, Instant.EPOCH, Instant.EPOCH);

    @BeforeEach
    void setUp() {
        given(portfolioService.getOrThrow(42L, 7L)).willReturn(PORTFOLIO);
    }

    private static HoldingResponse holding(String symbol, AssetType assetType, CurrencyCode currency,
            String sector, String marketValue) {
        InstrumentResponse instrument = new InstrumentResponse(1L, symbol, symbol + " Inc.", assetType, currency,
                "NYSE", sector);
        DataQualityDto dataQuality = new DataQualityDto(null, null, false);
        return new HoldingResponse(instrument, "10.000000",
                MoneyDto.of(new BigDecimal("100"), currency), MoneyDto.of(new BigDecimal("110"), currency),
                MoneyDto.of(new BigDecimal(marketValue), CurrencyCode.INR),
                MoneyDto.of(new BigDecimal("900"), CurrencyCode.INR),
                MoneyDto.of(new BigDecimal("100"), CurrencyCode.INR), "11.1100",
                MoneyDto.of(BigDecimal.ZERO, CurrencyCode.INR), "0.0000", null, dataQuality);
    }

    @Test
    void groupsByCurrencyAndWeightsSumToExactly100() {
        given(holdingViewService.list(42L, 7L, CurrencyCode.INR, false)).willReturn(List.of(
                holding("AAPL", AssetType.STOCK, CurrencyCode.USD, "Technology", "238156.20"),
                holding("SHEL", AssetType.STOCK, CurrencyCode.GBP, "Energy", "134090.00"),
                holding("RELIANCE", AssetType.STOCK, CurrencyCode.INR, "Energy", "14896.00")));

        AllocationResponse response = service.compute(42L, 7L, AllocationDimension.CURRENCY, null);

        assertThat(response.by()).isEqualTo(AllocationDimension.CURRENCY);
        assertThat(response.currency()).isEqualTo(CurrencyCode.INR);
        assertThat(response.slices()).hasSize(3);

        BigDecimal weightSum = response.slices().stream()
                .map(AllocationSliceDto::weightPct)
                .map(BigDecimal::new)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(weightSum).isEqualByComparingTo("100.0000");
    }

    @Test
    void groupsByAssetTypeCollapsingMultipleInstrumentsIntoOneSlice() {
        given(holdingViewService.list(42L, 7L, CurrencyCode.INR, false)).willReturn(List.of(
                holding("AAPL", AssetType.STOCK, CurrencyCode.USD, "Technology", "1000"),
                holding("MSFT", AssetType.STOCK, CurrencyCode.USD, "Technology", "1000"),
                holding("NIFTYBEES", AssetType.ETF, CurrencyCode.INR, "Diversified", "500")));

        AllocationResponse response = service.compute(42L, 7L, AllocationDimension.ASSET_TYPE, null);

        assertThat(response.slices()).hasSize(2);
        AllocationSliceDto stockSlice = response.slices().stream()
                .filter(s -> s.key().equals("STOCK")).findFirst().orElseThrow();
        assertThat(stockSlice.instrumentCount()).isEqualTo(2);
        assertThat(new BigDecimal(stockSlice.value().amount())).isEqualByComparingTo("2000.0000");
    }

    @Test
    void emptyPortfolioReturnsZeroTotalAndNoSlicesNeverAnError() {
        given(holdingViewService.list(42L, 7L, CurrencyCode.INR, false)).willReturn(List.of());

        AllocationResponse response = service.compute(42L, 7L, AllocationDimension.CURRENCY, null);

        assertThat(response.slices()).isEmpty();
        assertThat(response.total().amount()).isEqualTo("0.0000");
    }

    @Test
    void unpricedHoldingIsExcludedFromSlicesButFlagsStale() {
        HoldingResponse unpriced = new HoldingResponse(
                new InstrumentResponse(2L, "TSLAA", "Unpriced Inc.", AssetType.STOCK, CurrencyCode.USD, null, null),
                "5.000000", MoneyDto.of(new BigDecimal("50"), CurrencyCode.USD), null, null,
                MoneyDto.of(new BigDecimal("250"), CurrencyCode.INR), null, null,
                MoneyDto.of(BigDecimal.ZERO, CurrencyCode.INR), "0.0000", null,
                new DataQualityDto(null, null, true));
        given(holdingViewService.list(42L, 7L, CurrencyCode.INR, false)).willReturn(List.of(unpriced));

        AllocationResponse response = service.compute(42L, 7L, AllocationDimension.CURRENCY, null);

        assertThat(response.slices()).isEmpty();
        assertThat(response.dataQuality().stale()).isTrue();
    }

    @Test
    void requestedCurrencyOverridesPortfolioBaseCurrency() {
        given(holdingViewService.list(42L, 7L, CurrencyCode.USD, false)).willReturn(List.of(
                holding("AAPL", AssetType.STOCK, CurrencyCode.USD, "Technology", "1000")));

        AllocationResponse response = service.compute(42L, 7L, AllocationDimension.CURRENCY, CurrencyCode.USD);

        assertThat(response.currency()).isEqualTo(CurrencyCode.USD);
    }

    // ---- ordering and the "Other" fold (§13) ------------------------------------------------

    /** Holdings order is arbitrary to a reader. A pie's wedges and its legend both mean
     * "biggest to smallest", and {@code limit} could not mean "the top ones" without it. */
    @Test
    void slicesComeBackLargestFirstRegardlessOfHoldingsOrder() {
        given(holdingViewService.list(42L, 7L, CurrencyCode.INR, false)).willReturn(List.of(
                holding("SMALL", AssetType.STOCK, CurrencyCode.USD, "Technology", "100"),
                holding("BIG", AssetType.ETF, CurrencyCode.USD, "Diversified", "5000"),
                holding("MID", AssetType.BOND, CurrencyCode.USD, "Government", "900")));

        AllocationResponse response = service.compute(42L, 7L, AllocationDimension.ASSET_TYPE, null);

        assertThat(response.slices()).extracting(AllocationSliceDto::key)
                .containsExactly("ETF", "BOND", "STOCK");
    }

    private void givenTwelveSectors() {
        List<HoldingResponse> holdings = new java.util.ArrayList<>();
        // Descending values, so the expected fold is unambiguous: 1200, 1100, … 100.
        for (int i = 0; i < 12; i++) {
            holdings.add(holding("SYM" + i, AssetType.STOCK, CurrencyCode.USD,
                    "Sector" + (char) ('A' + i), String.valueOf(1200 - i * 100)));
        }
        given(holdingViewService.list(42L, 7L, CurrencyCode.INR, false)).willReturn(holdings);
    }

    @Test
    void foldsTheTailIntoExactlyLimitSlices() {
        givenTwelveSectors();

        AllocationResponse response = service.compute(42L, 7L, AllocationDimension.SECTOR, null, 8);

        assertThat(response.slices()).hasSize(8);
        assertThat(response.slices()).extracting(AllocationSliceDto::key)
                .containsExactly("SectorA", "SectorB", "SectorC", "SectorD", "SectorE", "SectorF",
                        "SectorG", "OTHER");
    }

    /** The whole reason this is server-side: summing decimal strings in a browser means summing
     * them as doubles, and the folded figure would drift from the total beside it. */
    @Test
    void theFoldedSliceSumsItsMembersExactly() {
        givenTwelveSectors();

        AllocationResponse response = service.compute(42L, 7L, AllocationDimension.SECTOR, null, 8);

        AllocationSliceDto other = response.slices().get(7);
        // The five smallest: 500 + 400 + 300 + 200 + 100.
        assertThat(new BigDecimal(other.value().amount())).isEqualByComparingTo("1500.0000");
        assertThat(other.instrumentCount()).isEqualTo(5);
    }

    @Test
    void weightsStillSumToExactly100AfterFolding() {
        givenTwelveSectors();

        AllocationResponse response = service.compute(42L, 7L, AllocationDimension.SECTOR, null, 8);

        BigDecimal weightSum = response.slices().stream()
                .map(AllocationSliceDto::weightPct)
                .map(BigDecimal::new)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(weightSum).isEqualByComparingTo("100.0000");
    }

    /** An "Other" of one is not a fold — it is a named slice with its name taken away. */
    @Test
    void aLimitAtOrAboveTheSliceCountFoldsNothing() {
        given(holdingViewService.list(42L, 7L, CurrencyCode.INR, false)).willReturn(List.of(
                holding("AAPL", AssetType.STOCK, CurrencyCode.USD, "Technology", "1000"),
                holding("NIFTYBEES", AssetType.ETF, CurrencyCode.INR, "Diversified", "500")));

        AllocationResponse response = service.compute(42L, 7L, AllocationDimension.ASSET_TYPE, null, 2);

        assertThat(response.slices()).extracting(AllocationSliceDto::key).containsExactly("STOCK", "ETF");
    }

    @Test
    void noLimitReturnsEverySliceAsBefore() {
        givenTwelveSectors();

        AllocationResponse response = service.compute(42L, 7L, AllocationDimension.SECTOR, null);

        assertThat(response.slices()).hasSize(12);
        assertThat(response.slices()).extracting(AllocationSliceDto::key).doesNotContain("OTHER");
    }
}
