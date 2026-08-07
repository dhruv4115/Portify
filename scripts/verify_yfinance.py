"""D0-B4 — prove yfinance can fetch data for a NASDAQ, an NSE and an LSE ticker.

Run with the venv at scripts/.venv:
    scripts\\.venv\\Scripts\\python.exe scripts\\verify_yfinance.py   (Windows)
    scripts/.venv/bin/python scripts/verify_yfinance.py              (Linux/Mac)

NOTE (see /docs/RISKS.md R14): the LSE (London Stock Exchange) result is quoted in
GBX (pence), not GBP. SHEL.L trading around 2900 means GBP 29.00. Ingestion on Day 2
must divide LSE prices by 100 before storing them, or a GBP portfolio ends up
100x too valuable.
"""

import yfinance as yf

print("=== NASDAQ: AAPL ===")
print(yf.Ticker("AAPL").history(period="5d"))

print("\n=== NSE: RELIANCE.NS ===")
print(yf.Ticker("RELIANCE.NS").history(period="5d"))

print("\n=== LSE: SHEL.L (quoted in GBX/pence, divide by 100 for GBP) ===")
print(yf.Ticker("SHEL.L").history(period="5d"))
