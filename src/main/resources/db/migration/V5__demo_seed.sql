-- day-6-dev-A.md D6-A1 — the demo portfolio, deterministic from a cold start.
--
-- NOTE ON THE VERSION NUMBER. The task names this file V4__demo_seed.sql, but V4 was taken by
-- V4__perf_indexes.sql on Day 5 (D5-A3) and an applied migration is never renumbered (ADR-0007).
-- V5 is the next free number in Dev A's V1-V9 range, so nothing else about the task changes.
--
-- Every value here is a literal. No NOW(), no CURRENT_DATE, no RAND(), no AUTO_INCREMENT id
-- written by hand -- ids are resolved by sub-select on the natural keys (google_sub, symbol,
-- portfolio name), because generated ids are not contractual and a fresh database may number
-- differently. `docker compose down -v && docker compose up` gives the same chart on every
-- machine, which is the whole point: a rehearsal against shifting data proves nothing.
--
-- Prices are REAL seeded closes: every (symbol, date) pair below is present in
-- V11__seed_price_history.sql, and every trade date has a rate in V12__seed_fx_rate.sql, so the
-- 18-month series has no gap it has to forward-fill across at a transaction boundary. The
-- holding rows at the bottom are ProjectionEngine's own output for the transactions above them
-- -- ProjectionRebuildConsistencyIT (D6-A2) is what proves that, and is the reason those
-- constants can be trusted rather than merely believed.
--
-- LIVE DEMO, sign-in. CurrentUserResolverImpl looks a user up by google_sub only, so signing in
-- with a real Google account JIT-provisions a brand-new empty row rather than landing on this
-- data. Before the rehearsal, point the demo user at your own sub:
--
--     UPDATE app_user SET google_sub = '<your real Google sub>' WHERE email = 'demo@protify.dev';
--
-- Leave demo-user-2 alone: it exists precisely so that requesting ITS portfolio while signed in
-- as the demo user returns 404, live, in front of the assessor (CLAUDE.md non-negotiable #3).

INSERT INTO app_user (google_sub, email, display_name) VALUES
    ('demo-user-1', 'demo@protify.dev',   'Priya Demo'),
    ('demo-user-2', 'second@protify.dev', 'Arjun Second');

INSERT INTO portfolio (user_id, name, base_currency)
SELECT u.id, 'Growth', 'INR' FROM app_user u WHERE u.google_sub = 'demo-user-1';

INSERT INTO portfolio (user_id, name, base_currency)
SELECT u.id, 'Retirement', 'INR' FROM app_user u WHERE u.google_sub = 'demo-user-2';

-- ---------------------------------------------------------------------------------------------
-- "Growth" -- 30 transactions, 2025-02-03 to 2026-07-16, USD + INR + GBP.
--
-- Shaped so the demo has something to point at:
--   DEPOSIT x4       cash arrives before it is spent, so the balance never goes negative on any
--                    day of the series (checked in INR at the real V12 rates, low water mark
--                    ~Rs 1.07 lakh on 2025-06-09). A negative cash balance reads as a bug on a
--                    projector even when it is legal.
--   DIVIDEND         AAPL, 2025-09-04. `price` is the TOTAL payout, not per-share (see
--                    TransactionMapper) -- quantity is the holding it was paid on.
--   SELL at a profit MSFT 2025-10-03 at 514.0941 against an avg cost of 410.1806.
--   SELL to zero     MSFT 2026-05-06 closes the position exactly: 15+10+5 bought, 10+20 sold.
--                    The row survives at quantity 0 carrying its realised P&L, which is what
--                    `includeZero` exists to show.
--   Three currencies plus STOCK / ETF / TREASURY, so the allocation pie has shape and the FX
--   contribution is visible rather than theoretical.
-- ---------------------------------------------------------------------------------------------

-- Cash transactions: instrument_id NULL, quantity 0 (TransactionService.REQUIRES_ZERO_QUANTITY).
INSERT INTO txn (portfolio_id, instrument_id, txn_type, quantity, price, fees, currency, executed_at, note)
SELECT p.id, NULL, 'DEPOSIT', 0.000000, x.price, 0.0000, 'INR', x.executed_at, x.note
FROM (VALUES
    ROW(2600000.0000, '2025-02-03 09:15:00', 'Opening funding'),
    ROW(1900000.0000, '2025-04-02 09:15:00', 'Quarterly top-up'),
    ROW(2100000.0000, '2025-07-28 09:15:00', 'Quarterly top-up'),
    -- 2025-10-31, not 2025-11-01: the 1st was a Saturday and V12 seeds no weekend rate, which
    -- would leave this deposit as the one row on the chart with nothing to convert it at.
    ROW(1800000.0000, '2025-10-31 09:15:00', 'Bonus')
) AS x(price, executed_at, note)
JOIN portfolio p ON p.name = 'Growth'
JOIN app_user  u ON u.id = p.user_id AND u.google_sub = 'demo-user-1';

INSERT INTO txn (portfolio_id, instrument_id, txn_type, quantity, price, fees, currency, executed_at, note)
SELECT p.id, i.id, x.txn_type, x.quantity, x.price, x.fees, x.currency, x.executed_at, NULL
FROM (VALUES
    ROW('RELIANCE',  'BUY',      200.000000, 1235.2327, 250.0000, 'INR', '2025-02-03 10:00:00'),
    ROW('AAPL',      'BUY',       40.000000,  226.5625,   5.0000, 'USD', '2025-02-03 10:30:00'),
    ROW('SHEL',      'BUY',      300.000000,   26.2546,   8.0000, 'GBP', '2025-02-03 11:00:00'),
    ROW('MSFT',      'BUY',       15.000000,  397.1132,   5.0000, 'USD', '2025-03-05 10:00:00'),
    ROW('SPY',       'BUY',       20.000000,  529.2537,   5.0000, 'USD', '2025-04-03 10:00:00'),
    ROW('NIFTYBEES', 'BUY',      500.000000,  257.1600, 100.0000, 'INR', '2025-04-04 10:00:00'),
    ROW('AAPL',      'BUY',       25.000000,  197.8446,   5.0000, 'USD', '2025-05-05 10:00:00'),
    ROW('MSFT',      'BUY',       10.000000,  460.1942,   5.0000, 'USD', '2025-06-04 10:00:00'),
    ROW('RELIANCE',  'BUY',      100.000000, 1436.3954, 150.0000, 'INR', '2025-06-09 10:00:00'),
    ROW('US10Y',     'BUY',      150.000000,   97.1552,  10.0000, 'USD', '2025-07-29 10:00:00'),
    ROW('SHEL',      'BUY',      200.000000,   26.8099,   8.0000, 'GBP', '2025-08-04 10:00:00'),
    ROW('AAPL',      'DIVIDEND',  65.000000,   16.2500,   0.0000, 'USD', '2025-09-04 10:00:00'),
    ROW('MSFT',      'SELL',      10.000000,  514.0941,   5.0000, 'USD', '2025-10-03 10:00:00'),
    ROW('SPY',       'BUY',       10.000000,  677.7252,   5.0000, 'USD', '2025-11-03 10:00:00'),
    ROW('RELIANCE',  'BUY',       75.000000, 1482.4458, 120.0000, 'INR', '2025-11-10 10:00:00'),
    ROW('AAPL',      'BUY',       20.000000,  283.6230,   5.0000, 'USD', '2025-12-03 10:00:00'),
    ROW('NIFTYBEES', 'BUY',      300.000000,  290.5900,  80.0000, 'INR', '2026-01-09 10:00:00'),
    ROW('SHEL',      'BUY',      150.000000,   27.7899,   8.0000, 'GBP', '2026-02-02 10:00:00'),
    ROW('AAPL',      'SELL',      15.000000,  257.2230,   5.0000, 'USD', '2026-03-06 10:00:00'),
    ROW('MSFT',      'BUY',        5.000000,  371.4854,   5.0000, 'USD', '2026-04-07 10:00:00'),
    ROW('MSFT',      'SELL',      20.000000,  413.0653,   5.0000, 'USD', '2026-05-06 10:00:00'),
    ROW('NIFTYBEES', 'BUY',      200.000000,  267.3000,  60.0000, 'INR', '2026-05-15 10:00:00'),
    ROW('RELIANCE',  'BUY',       50.000000, 1293.0000, 100.0000, 'INR', '2026-06-12 10:00:00'),
    ROW('SHEL',      'BUY',      100.000000,   28.9150,   8.0000, 'GBP', '2026-07-03 10:00:00'),
    ROW('NIFTYBEES', 'SELL',     200.000000,  275.3000,  60.0000, 'INR', '2026-07-13 10:00:00'),
    ROW('US10Y',     'BUY',       50.000000,   98.1236,  10.0000, 'USD', '2026-07-16 10:00:00')
) AS x(symbol, txn_type, quantity, price, fees, currency, executed_at)
JOIN portfolio  p ON p.name = 'Growth'
JOIN app_user   u ON u.id = p.user_id AND u.google_sub = 'demo-user-1'
JOIN instrument i ON i.symbol = x.symbol;

-- ---------------------------------------------------------------------------------------------
-- "Retirement" -- the second user. Small on purpose: its job is to be somebody else's portfolio
-- so GET /portfolios/{its id} as the demo user returns 404 live, and to give
-- ProjectionRebuildConsistencyIT a second portfolio to rebuild.
-- ---------------------------------------------------------------------------------------------

INSERT INTO txn (portfolio_id, instrument_id, txn_type, quantity, price, fees, currency, executed_at, note)
SELECT p.id, NULL, 'DEPOSIT', 0.000000, 500000.0000, 0.0000, 'INR', '2025-03-04 09:15:00', 'Opening funding'
FROM portfolio p
JOIN app_user u ON u.id = p.user_id AND u.google_sub = 'demo-user-2'
WHERE p.name = 'Retirement';

INSERT INTO txn (portfolio_id, instrument_id, txn_type, quantity, price, fees, currency, executed_at, note)
SELECT p.id, i.id, 'BUY', x.quantity, x.price, x.fees, x.currency, x.executed_at, NULL
FROM (VALUES
    ROW('NIFTYBEES', 400.000000,  247.4900,  80.0000, 'INR', '2025-03-04 10:00:00'),
    ROW('RELIANCE',   60.000000, 1372.1558,  90.0000, 'INR', '2025-09-08 10:00:00'),
    ROW('NIFTYBEES', 150.000000,  293.5600,  40.0000, 'INR', '2026-02-10 10:00:00')
) AS x(symbol, quantity, price, fees, currency, executed_at)
JOIN portfolio  p ON p.name = 'Retirement'
JOIN app_user   u ON u.id = p.user_id AND u.google_sub = 'demo-user-2'
JOIN instrument i ON i.symbol = x.symbol;

-- ---------------------------------------------------------------------------------------------
-- The projection.
--
-- These are not independent numbers -- they are what ProjectionEngine.project() returns for the
-- transactions above, computed with its exact arithmetic: weighted-average cost including fees,
-- DECIMAL(19,4) HALF_UP per MoneyUtils, SELL leaving avg_cost untouched and adding
-- (price - avg_cost) * quantity - fees to realised_pnl, sell-to-zero keeping the row.
--
-- Seeding them by hand rather than letting the app rebuild on boot is deliberate: it means
-- ProjectionRebuildConsistencyIT is comparing two genuinely independent derivations of the same
-- figures. If a future change to the engine silently alters the fold, this file stops agreeing
-- with it and that test goes red -- which is exactly the alarm ADR-0002 wants.
-- ---------------------------------------------------------------------------------------------

INSERT INTO holding (portfolio_id, instrument_id, quantity, avg_cost, realised_pnl)
SELECT p.id, i.id, x.quantity, x.avg_cost, x.realised_pnl
FROM (VALUES
    ROW('AAPL',       70.000000,  231.7185,  377.5675),
    ROW('MSFT',        0.000000,  410.1806,  961.1790),  -- closed to zero, P&L survives
    ROW('NIFTYBEES', 800.000000,  269.4570, 1108.6000),
    ROW('RELIANCE',  425.000000, 1334.4459,    0.0000),
    ROW('SHEL',      750.000000,   27.1071,    0.0000),
    ROW('SPY',        30.000000,  579.0775,    0.0000),
    ROW('US10Y',     200.000000,   97.4973,    0.0000)
) AS x(symbol, quantity, avg_cost, realised_pnl)
JOIN portfolio  p ON p.name = 'Growth'
JOIN app_user   u ON u.id = p.user_id AND u.google_sub = 'demo-user-1'
JOIN instrument i ON i.symbol = x.symbol;

INSERT INTO holding (portfolio_id, instrument_id, quantity, avg_cost, realised_pnl)
SELECT p.id, i.id, x.quantity, x.avg_cost, x.realised_pnl
FROM (VALUES
    ROW('NIFTYBEES', 550.000000,  260.2727, 0.0000),
    ROW('RELIANCE',   60.000000, 1373.6558, 0.0000)
) AS x(symbol, quantity, avg_cost, realised_pnl)
JOIN portfolio  p ON p.name = 'Retirement'
JOIN app_user   u ON u.id = p.user_id AND u.google_sub = 'demo-user-2'
JOIN instrument i ON i.symbol = x.symbol;
