-- Symbols are the CLEAN form (e.g. RELIANCE, SHEL) — provider-suffix mapping (e.g. RELIANCE.NS,
-- SHEL.L for yfinance) belongs in Dev B's marketdata adapter, not in this data.
INSERT INTO instrument (symbol, name, asset_type, currency, exchange, sector) VALUES
    -- USD
    ('AAPL',    'Apple Inc.',                             'STOCK',       'USD', 'NASDAQ',   'Technology'),
    ('MSFT',    'Microsoft Corporation',                  'STOCK',       'USD', 'NASDAQ',   'Technology'),
    ('NVDA',    'NVIDIA Corporation',                     'STOCK',       'USD', 'NASDAQ',   'Technology'),
    ('JPM',     'JPMorgan Chase & Co.',                   'STOCK',       'USD', 'NYSE',     'Financial Services'),
    ('SPY',     'SPDR S&P 500 ETF Trust',                 'ETF',         'USD', 'NYSEARCA', 'Diversified'),
    ('QQQ',     'Invesco QQQ Trust',                      'ETF',         'USD', 'NASDAQ',   'Technology'),
    ('AGG',     'iShares Core U.S. Aggregate Bond ETF',   'BOND',        'USD', 'NYSEARCA', 'Fixed Income'),
    ('US10Y',   'US 10-Year Treasury Note',                'TREASURY',    'USD', 'OTC',      'Government Bonds'),
    ('BTC-USD', 'Bitcoin',                                 'CRYPTO',      'USD', 'CCC',      'Cryptocurrency'),
    -- INR
    ('RELIANCE',    'Reliance Industries Limited',          'STOCK',       'INR', 'NSE',  'Energy'),
    ('TCS',         'Tata Consultancy Services Limited',    'STOCK',       'INR', 'NSE',  'Information Technology'),
    ('HDFCBANK',    'HDFC Bank Limited',                    'STOCK',       'INR', 'NSE',  'Financial Services'),
    ('NIFTYBEES',   'Nippon India ETF Nifty BeES',          'ETF',         'INR', 'NSE',  'Diversified'),
    ('SBIBLUECHIP', 'SBI Bluechip Fund',                    'MUTUAL_FUND', 'INR', 'AMFI', 'Equity - Large Cap'),
    -- GBP
    ('SHEL', 'Shell plc',                       'STOCK', 'GBP', 'LSE', 'Energy'),
    ('HSBA', 'HSBC Holdings plc',               'STOCK', 'GBP', 'LSE', 'Financial Services'),
    ('VUKE', 'Vanguard FTSE 100 UCITS ETF',     'ETF',   'GBP', 'LSE', 'Diversified'),
    -- EUR
    ('SAP',  'SAP SE',                          'STOCK', 'EUR', 'XETRA', 'Information Technology'),
    ('EXS1', 'iShares Core DAX UCITS ETF',      'ETF',   'EUR', 'XETRA', 'Diversified');
