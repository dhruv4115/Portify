import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ApiError } from '../api/client';
import {
  deleteTransaction,
  getHoldings,
  getPerformance,
  getPortfolio,
  getTransactions,
  updatePortfolio,
} from '../api/endpoints';
import type {
  CurrencyCode,
  Holding,
  Performance,
  PerformanceInterval,
  Portfolio,
  Transaction,
} from '../api/types';
import { AddTransactionForm } from '../components/AddTransactionForm';
import { AllocationChart } from '../components/AllocationChart';
import { AnalyticsPanel } from '../components/AnalyticsPanel';
import { ConfirmDeleteDialog } from '../components/ConfirmDeleteDialog';
import { ExportButtons } from '../components/ExportButtons';
import { HoldingsTable } from '../components/HoldingsTable';
import { ImportTransactions } from '../components/ImportTransactions';
import { InsightsCard } from '../components/InsightsCard';
import { PerformanceChart } from '../components/PerformanceChart';
import { StaleBadge } from '../components/StaleBadge';
import { toastFromError, useToast } from '../components/Toast';
import { TransactionList } from '../components/TransactionList';
import { useI18n } from '../i18n/I18nProvider';
import { Page } from '../layout/AppShell';
import { formatMoney } from '../lib/formatMoney';
import { rangeStart, type RangeKey } from '../lib/ranges';
import { Card } from '../ui/Card';
import { ErrorState, Skeleton } from '../ui/Feedback';
import { SelectInput } from '../ui/Field';
import { Icon } from '../ui/Icon';
import { IconButton } from '../ui/Button';
import { Delta, StatTile } from '../ui/Stat';
import { cx } from '../ui/cx';

const CURRENCIES: CurrencyCode[] = ['USD', 'EUR', 'GBP', 'INR'];
const TRANSACTION_PAGE_SIZE = 20;

const SECTIONS = [
  { id: 'performance', labelKey: 'performance.title' },
  { id: 'analytics', labelKey: 'analytics.title' },
  { id: 'allocation', labelKey: 'allocation.title' },
  { id: 'insights', labelKey: 'insights.title' },
  { id: 'holdings', labelKey: 'holdings.title' },
  { id: 'add-transaction', labelKey: 'txn.title' },
  { id: 'transactions', labelKey: 'transactions.title' },
] as const;

/**
 * The whole customer loop on one screen: browse → chart → add → remove.
 *
 * <p>The three reads are issued together and refetched together. A write changes all of them —
 * adding a transaction moves the chart and the holdings, and deleting one rebuilds the projection
 * from scratch — so refreshing only the list the user was looking at would leave the other two
 * quietly wrong.
 *
 * <p>Every panel stays on this one scrolling page rather than behind tabs. Splitting holdings,
 * allocation and transactions across routes would look tidier and cost a click each time somebody
 * wanted to compare two of them, which on a portfolio screen is most of the time. The section
 * rail on the left is the concession: it jumps without navigating, so nothing is ever more than
 * one keystroke away and nothing is ever hidden.
 */
export function PortfolioDetail() {
  const portfolioId = Number(useParams().id);
  const { showToast } = useToast();
  const { t } = useI18n();

  const [portfolio, setPortfolio] = useState<Portfolio | null>(null);
  const [holdings, setHoldings] = useState<Holding[]>([]);
  const [performance, setPerformance] = useState<Performance | null>(null);
  const [transactions, setTransactions] = useState<Transaction[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [pendingDelete, setPendingDelete] = useState<Transaction | null>(null);
  // Bumped on every successful reload so `AllocationChart` (which otherwise only refetches when
  // its own dimension/currency selector changes) knows a write may have changed the mix.
  const [dataVersion, setDataVersion] = useState(0);

  const [range, setRange] = useState<RangeKey>('ALL');
  const [interval, setIntervalChoice] = useState<PerformanceInterval>('DAILY');
  const [performanceRefreshing, setPerformanceRefreshing] = useState(false);
  /**
   * The live chart window, kept in a ref as well as in state.
   *
   * <p>`load` reads it from here rather than taking it as a dependency, so changing the range
   * refetches only the chart instead of tearing down and re-requesting holdings, transactions,
   * allocation and analytics — none of which the range affects.
   */
  const chartQuery = useRef({ range, interval });

  const [transactionPage, setTransactionPage] = useState(0);
  const [transactionTotal, setTransactionTotal] = useState(0);
  const [transactionsExhausted, setTransactionsExhausted] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);

  const [activeSection, setActiveSection] = useState<string>('performance');

  const load = useCallback(
    async (showSkeletons: boolean) => {
      if (showSkeletons) {
        setLoading(true);
      }
      setError(null);
      try {
        const { range: currentRange, interval: currentInterval } = chartQuery.current;
        const [nextPortfolio, nextHoldings, nextPerformance, nextTransactions] = await Promise.all([
          getPortfolio(portfolioId),
          getHoldings(portfolioId),
          getPerformance(portfolioId, {
            from: rangeStart(currentRange),
            interval: currentInterval,
          }),
          getTransactions(portfolioId, { size: TRANSACTION_PAGE_SIZE }),
        ]);
        setPortfolio(nextPortfolio);
        setHoldings(nextHoldings);
        setPerformance(nextPerformance);
        setTransactions(nextTransactions.content);
        setTransactionPage(nextTransactions.page ?? 0);
        setTransactionTotal(nextTransactions.totalElements ?? nextTransactions.content.length);
        setTransactionsExhausted(nextTransactions.last ?? true);
        setDataVersion((version) => version + 1);
      } catch (cause) {
        setError(
          cause instanceof ApiError && cause.status === 404
            ? t('portfolio.notFound')
            : t('portfolio.loadError'),
        );
      } finally {
        setLoading(false);
      }
    },
    // `t` is deliberately excluded: a language switch must not re-issue four requests. Error text
    // already on screen is a stale sentence in the previous language until the next reload, which
    // is a fair trade against refetching the whole page on a cosmetic change.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [portfolioId],
  );

  useEffect(() => {
    void load(true);
  }, [load]);

  /** Refetches only the chart. Used by the range and interval controls. */
  const reloadPerformance = useCallback(
    async (nextRange: RangeKey, nextInterval: PerformanceInterval) => {
      chartQuery.current = { range: nextRange, interval: nextInterval };
      setPerformanceRefreshing(true);
      try {
        setPerformance(
          await getPerformance(portfolioId, {
            from: rangeStart(nextRange),
            interval: nextInterval,
          }),
        );
      } catch (cause) {
        showToast(toastFromError(cause, t('performance.loadError')));
      } finally {
        setPerformanceRefreshing(false);
      }
    },
    [portfolioId, showToast, t],
  );

  function changeRange(next: RangeKey) {
    setRange(next);
    void reloadPerformance(next, interval);
  }

  function changeInterval(next: PerformanceInterval) {
    setIntervalChoice(next);
    void reloadPerformance(range, next);
  }

  /** Appends the next page instead of replacing, so filters keep working over everything loaded. */
  async function loadMoreTransactions() {
    setLoadingMore(true);
    try {
      const next = await getTransactions(portfolioId, {
        page: transactionPage + 1,
        size: TRANSACTION_PAGE_SIZE,
      });
      setTransactions((current) => [...current, ...next.content]);
      setTransactionPage(next.page);
      setTransactionsExhausted(next.last);
    } catch (cause) {
      showToast(toastFromError(cause, t('error.generic')));
    } finally {
      setLoadingMore(false);
    }
  }

  async function confirmDelete() {
    if (!pendingDelete) {
      return;
    }
    const removed = pendingDelete;
    setPendingDelete(null);
    // Optimistic: gone from the list the instant the user confirms. If the request fails, the
    // `load(false)` below resyncs with the server — which still has it — putting it straight back.
    setTransactions((current) => current.filter((txn) => txn.id !== removed.id));
    try {
      await deleteTransaction(portfolioId, removed.id);
      showToast({ variant: 'success', title: t('transactions.deleted') });
    } catch (cause) {
      showToast(toastFromError(cause, t('transactions.deleteFailed')));
    } finally {
      await load(false);
    }
  }

  async function changeBaseCurrency(next: CurrencyCode) {
    if (!portfolio || next === portfolio.baseCurrency) {
      return;
    }
    try {
      await updatePortfolio(portfolioId, { baseCurrency: next });
      await load(false);
      showToast({ variant: 'success', title: t('portfolio.currencySwitched', { code: next }) });
    } catch (cause) {
      showToast(toastFromError(cause, t('portfolio.currencySwitchFailed')));
    }
  }

  // Scroll-spy for the section rail. Guarded because jsdom has no IntersectionObserver, and a
  // missing one should cost the highlight, not the page.
  useEffect(() => {
    if (loading || typeof IntersectionObserver === 'undefined') {
      return;
    }
    const observer = new IntersectionObserver(
      (entries) => {
        const visible = entries
          .filter((entry) => entry.isIntersecting)
          .sort((left, right) => left.boundingClientRect.top - right.boundingClientRect.top)[0];
        if (visible) {
          setActiveSection(visible.target.id);
        }
      },
      { rootMargin: '-88px 0px -55% 0px', threshold: 0 },
    );

    for (const section of SECTIONS) {
      const element = document.getElementById(section.id);
      if (element) {
        observer.observe(element);
      }
    }
    return () => observer.disconnect();
  }, [loading]);

  if (error) {
    return (
      <Page>
        <ErrorState
          title={error}
          action={
            <Link to="/portfolios" className="btn btn-secondary">
              <Icon name="arrowLeft" size={15} className="rtl-flip" />
              {t('portfolio.backToPortfolios')}
            </Link>
          }
        />
      </Page>
    );
  }

  return (
    <Page>
      <nav aria-label={t('nav.breadcrumb')} className="mb-3">
        <Link
          to="/portfolios"
          className="link-quiet inline-flex items-center gap-1.5 text-[0.8125rem] font-medium"
        >
          <Icon name="arrowLeft" size={14} className="rtl-flip" />
          {t('portfolio.back')}
        </Link>
      </nav>

      <header className="mb-6">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div className="min-w-0">
            {portfolio ? (
              <h1 className="text-[1.5rem] font-bold tracking-[-0.03em] text-ink sm:text-[1.875rem]">
                {portfolio.name}
              </h1>
            ) : (
              <Skeleton className="h-9 w-56" />
            )}
          </div>

          {portfolio && (
            <div className="flex items-center gap-2">
              <div className="field field-inline flex-row items-center gap-2">
                <label className="field-label whitespace-nowrap" htmlFor="base-currency-switch">
                  {t('portfolio.baseCurrency')}
                </label>
                <SelectInput
                  id="base-currency-switch"
                  value={portfolio.baseCurrency}
                  onChange={(event) => void changeBaseCurrency(event.target.value as CurrencyCode)}
                  className="h-9 w-auto py-0"
                >
                  {CURRENCIES.map((code) => (
                    <option key={code} value={code}>
                      {code}
                    </option>
                  ))}
                </SelectInput>
              </div>
              <IconButton
                icon="refresh"
                label={t('portfolio.refresh')}
                onClick={() => void load(false)}
              />
            </div>
          )}
        </div>

        {portfolio && (
          <Card className="mt-5 animate-rise-in">
            <dl className="grid gap-5 sm:grid-cols-2 lg:grid-cols-4">
              <StatTile
                label={t('portfolio.totalValue')}
                value={formatMoney(portfolio.totalValue)}
                emphasis
                meta={<StaleBadge dataQuality={portfolio.dataQuality} />}
                sub={
                  <Delta
                    money={portfolio.unrealisedPnl}
                    percent={portfolio.unrealisedPnlPct}
                    size="sm"
                  />
                }
              />
              <StatTile
                label={t('portfolio.marketValue')}
                value={formatMoney(portfolio.marketValue)}
                sub={<span className="text-ink-3">{t('portfolio.costBasis')} {formatMoney(portfolio.costBasis)}</span>}
              />
              <StatTile
                label={t('portfolio.cash')}
                value={formatMoney(portfolio.cashBalance)}
                sub={<span className="text-ink-3">{portfolio.baseCurrency}</span>}
              />
              <StatTile
                label={t('portfolio.realisedPnl')}
                value={formatMoney(portfolio.realisedPnl)}
                sub={
                  <span className="text-ink-3">
                    {portfolio.holdingCount === 1
                      ? t('holdings.countOne')
                      : t('holdings.count', { count: portfolio.holdingCount })}
                  </span>
                }
              />
            </dl>
          </Card>
        )}
      </header>

      <div className="grid gap-6 xl:grid-cols-[10rem_minmax(0,1fr)]">
        {/* The rail is navigation *within* the page — no route change, no data refetch, no click
            cost. Hidden below xl, where the screen is short enough to scroll. */}
        <nav aria-label={t('portfolio.sections')} className="hidden xl:block">
          <div className="sticky top-24">
            <p className="mb-2 px-2.5 text-[0.6875rem] font-semibold uppercase tracking-[0.07em] text-ink-3">
              {t('portfolio.sections')}
            </p>
            <ul className="space-y-0.5">
              {SECTIONS.map((section) => (
                <li key={section.id}>
                  <a
                    href={`#${section.id}`}
                    aria-current={activeSection === section.id ? 'true' : undefined}
                    className={cx(
                      'block rounded-lg px-2.5 py-1.5 text-[0.8125rem] transition-colors duration-200',
                      activeSection === section.id
                        ? 'bg-brand-soft font-medium text-brand-text'
                        : 'text-ink-3 hover:bg-surface-2 hover:text-ink',
                    )}
                  >
                    {t(section.labelKey)}
                  </a>
                </li>
              ))}
            </ul>
          </div>
        </nav>

        <div className="min-w-0 space-y-5">
          <PerformanceChart
            performance={performance}
            loading={loading}
            range={range}
            onRangeChange={changeRange}
            interval={interval}
            onIntervalChange={changeInterval}
            refreshing={performanceRefreshing}
          />

          <AnalyticsPanel
            portfolioId={portfolioId}
            performance={performance}
            loading={loading}
            version={dataVersion}
          />

          <div className="grid gap-5 xl:grid-cols-2">
            {portfolio && (
              <AllocationChart
                portfolioId={portfolioId}
                currency={portfolio.baseCurrency}
                version={dataVersion}
              />
            )}
            <InsightsCard portfolioId={portfolioId} version={dataVersion} />
          </div>

          <HoldingsTable holdings={holdings} loading={loading} />

          {!loading && (
            <div className="grid gap-5 xl:grid-cols-2">
              <ExportButtons portfolioId={portfolioId} holdings={holdings} />
              {/* An import rewrites the projection, so it refetches everything the page shows —
                  the same `load(false)` a hand-added transaction triggers, for the same reason. */}
              <ImportTransactions portfolioId={portfolioId} onImported={() => void load(false)} />
            </div>
          )}

          {portfolio && (
            <AddTransactionForm
              portfolioId={portfolioId}
              baseCurrency={portfolio.baseCurrency}
              onAdded={() => void load(false)}
            />
          )}

          <TransactionList
            transactions={transactions}
            loading={loading}
            onDelete={setPendingDelete}
            total={transactionTotal}
            hasMore={!transactionsExhausted}
            loadingMore={loadingMore}
            onLoadMore={() => void loadMoreTransactions()}
          />
        </div>
      </div>

      {pendingDelete && (
        <ConfirmDeleteDialog
          transaction={pendingDelete}
          onConfirm={() => void confirmDelete()}
          onCancel={() => setPendingDelete(null)}
        />
      )}
    </Page>
  );
}
