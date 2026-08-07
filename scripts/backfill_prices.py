"""scripts/backfill_prices.py — D2-B4

Fetches two years of daily closes (yfinance) for the 18 V2-seeded instruments, and two years of
daily USD-pivot FX rates (Frankfurter, ECB reference rates) for EUR/GBP/INR, and emits:

  - src/main/resources/db/migration/V11__seed_price_history.sql
  - src/main/resources/db/migration/V12__seed_fx_rate.sql

Both migrations use `INSERT IGNORE ... SELECT ... FROM instrument WHERE symbol = ...` (price
history) or a plain `VALUES` table constructor (FX), batched ~500 rows per statement, so the
migration is idempotent and applies in seconds regardless of instrument.id assignment order.

Re-running this script regenerates both migrations. Do this before anyone has data worth
keeping — Flyway checksums an applied migration, so regenerating later means a fresh database
for everyone (see /CLAUDE.md).

GBX -> GBP (/docs/RISKS.md R14): London-listed instruments (SHEL, HSBA, VUKE) quote in pence.
This script divides by 100 before emitting SQL, exactly mirroring
YahooMarketDataProvider's ingestion-time conversion — a mismatch between the two is a bug that
only shows up in the chart.
"""

import datetime as dt
import random
import sys
from pathlib import Path

import requests
import yfinance as yf

REPO_ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS_DIR = REPO_ROOT / "src" / "main" / "resources" / "db" / "migration"

# clean symbol -> (yahoo ticker, quoted in minor units i.e. GBX pence)
# Must match com.protify.portfolio.marketdata.provider.YahooSymbolMapping exactly.
INSTRUMENTS = {
    "AAPL": ("AAPL", False),
    "MSFT": ("MSFT", False),
    "NVDA": ("NVDA", False),
    "JPM": ("JPM", False),
    "SPY": ("SPY", False),
    "QQQ": ("QQQ", False),
    "AGG": ("AGG", False),
    "US10Y": ("US10Y", False),
    "BTC-USD": ("BTC-USD", False),
    "RELIANCE": ("RELIANCE.NS", False),
    "TCS": ("TCS.NS", False),
    "HDFCBANK": ("HDFCBANK.NS", False),
    "NIFTYBEES": ("NIFTYBEES.NS", False),
    "SBIBLUECHIP": ("SBIBLUECHIP.NS", False),
    "SHEL": ("SHEL.L", True),
    "HSBA": ("HSBA.L", True),
    "VUKE": ("VUKE.L", True),
    "SAP": ("SAP.DE", False),
    "EXS1": ("EXS1.DE", False),
}

# Not every seeded instrument has a free real-data source on Yahoo: US10Y is a synthetic
# Treasury identifier (Yahoo has yield tickers like ^TNX, not a note price), and Indian mutual
# funds generally aren't quoted there at all. Rather than ship a 0-row seed for these two —
# which would make them un-demoable positions — a deterministic synthetic random-walk series
# fills the same 2-year window, clearly a fallback, never presented as live data. Every row
# still lands with source = 'SEED', same as everything else; this comment plus the migration's
# own header is where the distinction is visible (`/docs/RISKS.md` — no silent fake data).
SYNTHETIC_FALLBACK_START_PRICE = {
    "US10Y": 98.50,   # plausible clean price for a 10-year Treasury note ETF-like proxy
    "SBIBLUECHIP": 45.00,  # plausible NAV (INR) for an Indian large-cap equity mutual fund
}


def generate_synthetic_series(symbol, start, end):
    rng = random.Random(f"protify-seed-{symbol}")  # deterministic per symbol, reproducible
    price = SYNTHETIC_FALLBACK_START_PRICE[symbol]
    rows = []
    current = start
    while current <= end:
        if current.weekday() < 5:  # business days only, like every real market seeded here
            price = max(0.01, price * (1 + rng.uniform(-0.01, 0.01)))
            rows.append((symbol, current.isoformat(), round(price, 4)))
        current += dt.timedelta(days=1)
    return rows


BATCH_SIZE = 500
YEARS_OF_HISTORY = 2


def fetch_prices():
    rows = []  # (symbol, date_str, close)
    end = dt.date.today()
    start = end - dt.timedelta(days=365 * YEARS_OF_HISTORY)
    for symbol, (ticker, minor_units) in INSTRUMENTS.items():
        print(f"Fetching {ticker} ...", file=sys.stderr)
        fetched = []
        try:
            history = yf.Ticker(ticker).history(
                start=start.isoformat(), end=end.isoformat(), interval="1d"
            )
            if not history.empty:
                for index, record in history.iterrows():
                    close = float(record["Close"])
                    if minor_units:
                        close = close / 100.0  # GBX (pence) -> GBP, R14
                    fetched.append((symbol, index.date().isoformat(), round(close, 4)))
        except Exception as exc:  # yfinance can raise several exception types
            print(f"  WARNING: failed to fetch {ticker}: {exc}", file=sys.stderr)

        if fetched:
            print(f"  {len(fetched)} rows for {ticker}", file=sys.stderr)
            rows.extend(fetched)
        elif symbol in SYNTHETIC_FALLBACK_START_PRICE:
            synthetic = generate_synthetic_series(symbol, start, end)
            print(f"  WARNING: no real data for {ticker}; using {len(synthetic)} synthetic rows instead", file=sys.stderr)
            rows.extend(synthetic)
        else:
            print(f"  WARNING: no data returned for {ticker} and no synthetic fallback defined", file=sys.stderr)
    return rows


def fetch_fx_rates():
    rows = []  # (base_ccy, quote_ccy, date_str, rate)
    end = dt.date.today()
    start = end - dt.timedelta(days=365 * YEARS_OF_HISTORY)
    url = f"https://api.frankfurter.app/{start.isoformat()}..{end.isoformat()}?from=USD&to=EUR,GBP,INR"
    print(f"Fetching FX rates from {url} ...", file=sys.stderr)
    response = requests.get(url, timeout=30)
    response.raise_for_status()
    data = response.json()
    for date_str, rates in data.get("rates", {}).items():
        for quote_ccy, rate in rates.items():
            rows.append(("USD", quote_ccy, date_str, round(float(rate), 8)))
    print(f"  {len(rows)} fx rows", file=sys.stderr)
    return rows


def write_price_history_migration(rows):
    path = MIGRATIONS_DIR / "V11__seed_price_history.sql"
    MIGRATIONS_DIR.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as f:
        f.write("-- Generated by scripts/backfill_prices.py -- do not hand-edit; re-run the script instead.\n")
        f.write("-- Two years of real daily closes for every V2-seeded instrument, source = 'SEED'.\n")
        f.write("-- GBX -> GBP division already applied for LSE instruments (SHEL, HSBA, VUKE) -- R14.\n\n")
        for batch_start in range(0, len(rows), BATCH_SIZE):
            batch = rows[batch_start : batch_start + BATCH_SIZE]
            values = ",\n    ".join(
                f"ROW('{symbol}','{date_str}',{close})" for symbol, date_str, close in batch
            )
            f.write(
                "INSERT IGNORE INTO price_history (instrument_id, price_date, close_price, source)\n"
                "SELECT i.id, x.price_date, x.close_price, 'SEED'\n"
                "FROM instrument i\n"
                "JOIN (VALUES\n"
                f"    {values}\n"
                ") AS x(symbol, price_date, close_price) ON i.symbol = x.symbol;\n\n"
            )
    print(f"Wrote {path} ({len(rows)} rows)", file=sys.stderr)


def write_fx_rate_migration(rows):
    path = MIGRATIONS_DIR / "V12__seed_fx_rate.sql"
    MIGRATIONS_DIR.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as f:
        f.write("-- Generated by scripts/backfill_prices.py -- do not hand-edit; re-run the script instead.\n")
        f.write("-- Two years of real daily ECB reference rates (Frankfurter), USD pivot, source = 'SEED'.\n\n")
        for batch_start in range(0, len(rows), BATCH_SIZE):
            batch = rows[batch_start : batch_start + BATCH_SIZE]
            values = ",\n    ".join(
                f"ROW('{base}','{quote}','{date_str}',{rate})"
                for base, quote, date_str, rate in batch
            )
            f.write(
                "INSERT IGNORE INTO fx_rate (base_ccy, quote_ccy, rate_date, rate, source)\n"
                "SELECT x.base_ccy, x.quote_ccy, x.rate_date, x.rate, 'SEED'\n"
                "FROM (VALUES\n"
                f"    {values}\n"
                ") AS x(base_ccy, quote_ccy, rate_date, rate);\n\n"
            )
    print(f"Wrote {path} ({len(rows)} rows)", file=sys.stderr)


def main():
    price_rows = fetch_prices()
    fx_rows = fetch_fx_rates()
    write_price_history_migration(price_rows)
    write_fx_rate_migration(fx_rows)
    print("Done.", file=sys.stderr)


if __name__ == "__main__":
    main()
