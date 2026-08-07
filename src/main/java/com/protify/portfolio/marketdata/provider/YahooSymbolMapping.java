package com.protify.portfolio.marketdata.provider;

import com.protify.portfolio.common.enums.CurrencyCode;

import java.util.Map;

/**
 * D2-B1 — symbol mapping lives in the adapter, not the database: {@code instrument.symbol}
 * holds the clean symbol ({@code RELIANCE}, {@code SHEL}); this maps it to the ticker Yahoo's
 * chart endpoint expects, and records which currency (and GBX→GBP conversion) applies. The
 * port's {@code symbol} parameter carries no exchange, so a lookup table is the only option —
 * agreed with Dev A per {@code /docs/PLAN.md} against the 18 instruments seeded in
 * {@code V2__seed_instruments.sql}.
 */
final class YahooSymbolMapping {

    record Mapping(String providerSymbol, CurrencyCode currency, boolean quotedInMinorUnits) {
    }

    // NSE and LSE quote in their exchange's minor unit for LSE only (GBX/pence); NSE does not.
    private static final Map<String, Mapping> CLEAN_TO_MAPPING = Map.ofEntries(
            // USD — ticker unchanged
            Map.entry("AAPL", new Mapping("AAPL", CurrencyCode.USD, false)),
            Map.entry("MSFT", new Mapping("MSFT", CurrencyCode.USD, false)),
            Map.entry("NVDA", new Mapping("NVDA", CurrencyCode.USD, false)),
            Map.entry("JPM", new Mapping("JPM", CurrencyCode.USD, false)),
            Map.entry("SPY", new Mapping("SPY", CurrencyCode.USD, false)),
            Map.entry("QQQ", new Mapping("QQQ", CurrencyCode.USD, false)),
            Map.entry("AGG", new Mapping("AGG", CurrencyCode.USD, false)),
            Map.entry("US10Y", new Mapping("US10Y", CurrencyCode.USD, false)),
            Map.entry("BTC-USD", new Mapping("BTC-USD", CurrencyCode.USD, false)),
            // NSE — .NS suffix, INR, not minor units
            Map.entry("RELIANCE", new Mapping("RELIANCE.NS", CurrencyCode.INR, false)),
            Map.entry("TCS", new Mapping("TCS.NS", CurrencyCode.INR, false)),
            Map.entry("HDFCBANK", new Mapping("HDFCBANK.NS", CurrencyCode.INR, false)),
            Map.entry("NIFTYBEES", new Mapping("NIFTYBEES.NS", CurrencyCode.INR, false)),
            Map.entry("SBIBLUECHIP", new Mapping("SBIBLUECHIP.NS", CurrencyCode.INR, false)),
            // LSE — .L suffix, GBP, quoted in GBX (pence) — /docs/RISKS.md R14
            Map.entry("SHEL", new Mapping("SHEL.L", CurrencyCode.GBP, true)),
            Map.entry("HSBA", new Mapping("HSBA.L", CurrencyCode.GBP, true)),
            Map.entry("VUKE", new Mapping("VUKE.L", CurrencyCode.GBP, true)),
            // XETRA — .DE suffix, EUR
            Map.entry("SAP", new Mapping("SAP.DE", CurrencyCode.EUR, false)),
            Map.entry("EXS1", new Mapping("EXS1.DE", CurrencyCode.EUR, false)));

    private YahooSymbolMapping() {
    }

    /** Falls back to the symbol as-is, assumed USD — covers any instrument not yet mapped. */
    static Mapping resolve(String cleanSymbol) {
        String normalised = cleanSymbol.strip().toUpperCase();
        return CLEAN_TO_MAPPING.getOrDefault(normalised, new Mapping(normalised, CurrencyCode.USD, false));
    }
}
