import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { getPortfolios } from '../api/endpoints';
import type { Portfolio } from '../api/types';
import { PortfolioCard } from '../components/PortfolioCard';
import { useI18n } from '../i18n/I18nProvider';
import { Page, PageHeader } from '../layout/AppShell';
import { formatMoney } from '../lib/formatMoney';
import { comparePercent, sumByCurrency } from '../lib/money';
import { Card, SectionCard } from '../ui/Card';
import { EmptyState, ErrorState, Skeleton } from '../ui/Feedback';
import { Icon, type IconName } from '../ui/Icon';
import { Delta } from '../ui/Stat';

/**
 * The landing page: everything the user owns, at a glance, before drilling into one portfolio.
 *
 * <p>The headline figure is deliberately *not* a single net-worth number. Portfolios can each have
 * their own base currency, and adding rupees to dollars requires a rate for a date that only the
 * server can supply honestly. So the page reports a real total per currency and says so — the
 * same "degraded but truthful beats confident but wrong" rule the stale-price badge follows.
 */
export function Overview() {
  const { t } = useI18n();
  const [portfolios, setPortfolios] = useState<Portfolio[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(false);
    getPortfolios()
      .then((loaded) => !cancelled && setPortfolios(loaded))
      .catch(() => !cancelled && setError(true))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, []);

  const totals = useMemo(
    () => sumByCurrency(portfolios.map((portfolio) => portfolio.totalValue)),
    [portfolios],
  );

  const holdingCount = portfolios.reduce((sum, portfolio) => sum + portfolio.holdingCount, 0);

  // Ranked on percentage rather than absolute P&L: a percentage is the one figure that compares
  // meaningfully across portfolios of different sizes *and* different currencies.
  const ranked = useMemo(
    () =>
      [...portfolios]
        .filter((portfolio) => portfolio.unrealisedPnlPct !== null)
        .sort((left, right) => comparePercent(right.unrealisedPnlPct, left.unrealisedPnlPct)),
    [portfolios],
  );
  const best = ranked[0];
  const worst = ranked.length > 1 ? ranked[ranked.length - 1] : undefined;

  return (
    <Page>
      <PageHeader
        eyebrow={<p className="mb-1 text-[0.8125rem] font-medium text-brand-text">{t('overview.greeting')}</p>}
        title={t('overview.title')}
        description={t('overview.subtitle')}
        actions={
          <Link to="/portfolios" className="btn btn-secondary">
            {t('nav.portfolios')}
            <Icon name="arrowRight" size={15} className="rtl-flip" />
          </Link>
        }
      />

      {error && <ErrorState title={t('portfolios.loadError')} />}

      {loading && !error && (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {[0, 1, 2, 3].map((index) => (
            <Card key={index}>
              <Skeleton className="h-3 w-20" />
              <Skeleton className="mt-3 h-7 w-32" />
            </Card>
          ))}
        </div>
      )}

      {!loading && !error && portfolios.length === 0 && (
        <Card>
          <EmptyState
            icon="wallet"
            title={t('overview.empty')}
            body={t('overview.emptyHint')}
            action={
              <Link to="/portfolios?new=1" className="btn btn-primary">
                <Icon name="plus" size={15} />
                {t('portfolios.new')}
              </Link>
            }
          />
        </Card>
      )}

      {!loading && !error && portfolios.length > 0 && (
        <div className="space-y-6">
          {/* One full-width hero rather than a wide card beside a stack of small ones. The stack
              forced the grid row to the height of three cards and left the totals floating in
              half a card of dead space; folding the counts into the same card fills it with
              information instead of padding. */}
          <Card className="animate-rise-in" aria-label={t('overview.totalsByCurrency')}>
            <div className="flex items-start justify-between gap-3">
              <div>
                <p className="card-eyebrow">{t('overview.totalsByCurrency')}</p>
                <p className="mt-0.5 text-[0.75rem] text-ink-3">{t('overview.totalsByCurrencyHint')}</p>
              </div>
              <span className="grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-brand-soft text-brand-text">
                <Icon name="wallet" size={17} />
              </span>
            </div>

            <div className="mt-6 flex flex-wrap items-end gap-x-12 gap-y-6">
              {totals.map((entry, index) => (
                <div key={entry.currency} className={`animate-count-up stagger-${Math.min(index, 11)}`}>
                  <p className="text-[0.6875rem] font-semibold uppercase tracking-[0.07em] text-ink-3">
                    {entry.currency}
                  </p>
                  <p className="mt-1 text-[1.75rem] font-bold leading-tight tracking-[-0.03em] tabular-nums text-ink">
                    {formatMoney(entry.total)}
                  </p>
                  <p className="mt-1 text-[0.75rem] text-ink-3">
                    {entry.count === 1
                      ? t('portfolios.countOne')
                      : t('portfolios.count', { count: entry.count })}
                  </p>
                </div>
              ))}
            </div>

            <div className="mt-6 flex flex-wrap items-center justify-between gap-x-10 gap-y-3 border-t border-hairline-subtle pt-4">
              <dl className="flex flex-wrap gap-x-10 gap-y-3">
                <SummaryCount
                  label={t('overview.portfolioCount')}
                  value={portfolios.length}
                  icon="layers"
                />
                <SummaryCount label={t('overview.holdingCount')} value={holdingCount} icon="activity" />
                <SummaryCount label={t('overview.currencyCount')} value={totals.length} icon="globe" />
              </dl>

              {totals.length > 1 && (
                <p className="flex max-w-md items-start gap-1.5 text-[0.75rem] text-ink-3">
                  <Icon name="info" size={13} className="mt-0.5 shrink-0" />
                  {t('overview.mixedCurrencyNote', { count: totals.length })}
                </p>
              )}
            </div>
          </Card>

          {(best || worst) && (
            <section aria-label={t('overview.bestPerformer')} className="grid gap-4 sm:grid-cols-2">
              {best && <PerformerCard portfolio={best} label={t('overview.bestPerformer')} tone="pos" />}
              {worst && <PerformerCard portfolio={worst} label={t('overview.worstPerformer')} tone="neg" />}
            </section>
          )}

          <SectionCard
            title={t('overview.allPortfolios')}
            icon="layers"
            actions={
              <Link to="/portfolios" className="btn btn-ghost btn-sm">
                {t('overview.viewAll')}
                <Icon name="arrowRight" size={13} className="rtl-flip" />
              </Link>
            }
          >
            <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
              {portfolios.slice(0, 6).map((portfolio, index) => (
                <PortfolioCard key={portfolio.id} portfolio={portfolio} index={index} />
              ))}
            </div>
          </SectionCard>
        </div>
      )}
    </Page>
  );
}

function PerformerCard({
  portfolio,
  label,
  tone,
}: {
  portfolio: Portfolio;
  label: string;
  tone: 'pos' | 'neg';
}) {
  const { t } = useI18n();

  return (
    <Link
      to={`/portfolios/${portfolio.id}`}
      className="card card-sheen card-interactive group animate-rise-in"
    >
      <div className="flex items-center justify-between gap-3">
        <p className="card-eyebrow">{label}</p>
        <span
          className={`grid h-8 w-8 place-items-center rounded-lg ${
            tone === 'pos' ? 'bg-pos-soft text-pos' : 'bg-neg-soft text-neg'
          }`}
        >
          <Icon name={tone === 'pos' ? 'trendUp' : 'trendDown'} size={15} strokeWidth={2.1} />
        </span>
      </div>
      <p className="mt-2 truncate text-[1.0625rem] font-semibold tracking-[-0.015em] text-ink">
        {portfolio.name}
      </p>
      <div className="mt-1 flex flex-wrap items-baseline gap-x-3">
        <span className="text-[0.875rem] tabular-nums text-ink-2">{formatMoney(portfolio.totalValue)}</span>
        <Delta money={portfolio.unrealisedPnl} percent={portfolio.unrealisedPnlPct} size="sm" />
      </div>
      {/* Holdings and currency, not the percentage again — the delta above already said that. */}
      <p className="mt-3 text-[0.75rem] text-ink-3">
        {portfolio.holdingCount === 1
          ? t('portfolios.holdingsOne')
          : t('portfolios.holdings', { count: portfolio.holdingCount })}{' '}
        · {portfolio.baseCurrency}
      </p>
    </Link>
  );
}

function SummaryCount({ label, value, icon }: { label: string; value: number; icon: IconName }) {
  return (
    <div>
      <dt className="flex items-center gap-1.5 text-[0.6875rem] font-semibold uppercase tracking-[0.07em] text-ink-3">
        <Icon name={icon} size={12} />
        {label}
      </dt>
      <dd className="mt-0.5 text-[1.0625rem] font-semibold tabular-nums tracking-[-0.015em] text-ink">
        {value}
      </dd>
    </div>
  );
}
