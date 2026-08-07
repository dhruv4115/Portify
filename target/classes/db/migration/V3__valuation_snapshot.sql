-- day-5-dev-A.md D5-A2 / ADR-0010's "adopted as a cache over the fold on Day 5".
--
-- portfolio_valuation_daily has existed since V1__baseline.sql and has been unused all week.
-- Materialising it needs four columns the baseline did not anticipate, because a snapshot has to
-- be able to stand in for a computed PerformancePoint exactly, or it is not a cache — it is a
-- second, slightly different answer:
--
--   currency     the series can be requested in any currency (PLAN.md §2.1: base currency is a
--                presentation concern). Without this the unique key would let a row computed in
--                USD be served for an INR request, silently, and a base-currency PATCH would
--                need an invalidation hook reaching from portfolio/ into valuation/.
--   filled       whether that day's price or FX rate was forward-filled from an earlier date.
--                A weekend served from a snapshot must still read as forward-filled on the chart.
--   price_as_of  the dates genuinely used, which is what makes staleness reportable rather than
--   rate_as_of   invisible. Recomputing them would mean re-fetching the price series, which is
--                exactly the query the snapshot exists to avoid.
--
-- The table is empty in every environment, so the DEFAULTs below only satisfy NOT NULL; they
-- never backfill a real row.

ALTER TABLE portfolio_valuation_daily
    ADD COLUMN currency    CHAR(3)    NOT NULL DEFAULT 'USD' AFTER valuation_date,
    ADD COLUMN filled      TINYINT(1) NOT NULL DEFAULT 0,
    ADD COLUMN price_as_of DATE       NULL,
    ADD COLUMN rate_as_of  DATE       NULL;

-- One row per portfolio per day per currency.
--
-- The order of these three statements is not stylistic. uk_val (portfolio_id, valuation_date) is
-- the index InnoDB uses to satisfy fk_val_portfolio, so dropping it first fails outright with
-- "Cannot drop index 'uk_val': needed in a foreign key constraint" (MySQL 1553). Adding the
-- wider key first gives the foreign key a leading-column index to fall back on, and the rename
-- afterwards keeps the constraint under the name V1__baseline.sql and REFERENCE_DESIGN §1 use.
ALTER TABLE portfolio_valuation_daily
    ADD CONSTRAINT uk_val_ccy UNIQUE (portfolio_id, valuation_date, currency);

ALTER TABLE portfolio_valuation_daily DROP INDEX uk_val;

ALTER TABLE portfolio_valuation_daily RENAME INDEX uk_val_ccy TO uk_val;
