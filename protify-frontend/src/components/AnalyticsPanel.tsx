import { useEffect, useState } from 'react';
import { getAllTransactions } from '../api/endpoints';
import type { Performance, Transaction } from '../api/types';
import { useI18n } from '../i18n/I18nProvider';
import { computeAnalytics } from '../lib/analytics';
import { formatPercent } from '../lib/formatMoney';
import { SectionCard } from '../ui/Card';
import { EmptyState, Skeleton } from '../ui/Feedback';
import { Icon } from '../ui/Icon';
import { cx } from '../ui/cx';

interface Props {
  portfolioId: number;
  performance: Performance | null;
  loading?: boolean;
  /** Bumped by the parent whenever a write may have changed the transaction history. */
  version: number;
}

/**
 * day-5-dev-C.md D5-C2. TWR is labelled explicitly as "time-weighted" rather than just
 * "return" — it is the number that needs explaining (a deposit or withdrawal never counts as a
 * gain or a loss here), and spelling that out is what separates "the product understands
 * finance" from a bare percentage.
 *
 * <p>Each figure carries a plain-language explanation on hover and in its `title`, because a
 * dashboard that shows "max drawdown" without saying what it means is showing it to people who
 * already knew.
 */
export function AnalyticsPanel({ portfolioId, performance, loading = false, version }: Props) {
  const { t } = useI18n();
  const [transactions, setTransactions] = useState<Transaction[]>([]);
  const [transactionsLoading, setTransactionsLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setTransactionsLoading(true);
    getAllTransactions(portfolioId)
      .then((all) => {
        if (!cancelled) {
          setTransactions(all);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setTransactions([]);
        }
      })
      .finally(() => {
        if (!cancelled) {
          setTransactionsLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [portfolioId, version]);

  if (loading || transactionsLoading) {
    return (
      <SectionCard id="analytics" title={t('analytics.title')} icon="activity" busy>
        <div data-testid="analytics-skeleton" className="grid gap-4 sm:grid-cols-3 lg:grid-cols-5">
          {[0, 1, 2, 3, 4].map((index) => (
            <div key={index}>
              <Skeleton className="h-3 w-16" />
              <Skeleton className="mt-2 h-6 w-20" />
            </div>
          ))}
        </div>
      </SectionCard>
    );
  }

  const analytics = computeAnalytics(performance, transactions);

  if (analytics.twr === null) {
    return (
      <SectionCard id="analytics" title={t('analytics.title')} icon="activity">
        <EmptyState icon="activity" inset testId="analytics-empty" title={t('analytics.empty')} />
      </SectionCard>
    );
  }

  return (
    <SectionCard id="analytics" title={t('analytics.title')} icon="activity">
      <dl data-testid="analytics-panel" className="grid gap-5 sm:grid-cols-3 lg:grid-cols-5">
        <Metric
          label={t('analytics.twr')}
          hint={t('analytics.twrHint')}
          value={analytics.twr}
          signed
        />
        <Metric
          label={t('analytics.annualised')}
          hint={t('analytics.annualisedHint')}
          value={analytics.annualisedReturn}
          signed
        />
        <Metric
          label={t('analytics.maxDrawdown')}
          hint={t('analytics.maxDrawdownHint')}
          value={analytics.maxDrawdown}
        />
        <Metric
          label={t('analytics.bestDay')}
          value={analytics.bestDay?.value ?? null}
          caption={analytics.bestDay?.date}
          signed
        />
        <Metric
          label={t('analytics.worstDay')}
          value={analytics.worstDay?.value ?? null}
          caption={analytics.worstDay?.date}
          signed
        />
      </dl>
    </SectionCard>
  );
}

/**
 * One analytics figure.
 *
 * <p>`signed` colours the value by direction and pairs it with an arrow — the same icon-plus-sign
 * rule the P&L figures follow, so the colour is never the only carrier of meaning. Drawdown is
 * unsigned: it is always a fall, so a red arrow beside it would be telling the reader something
 * they already know from the label.
 */
function Metric({
  label,
  value,
  hint,
  caption,
  signed = false,
}: {
  label: string;
  value: number | null;
  hint?: string;
  caption?: string;
  signed?: boolean;
}) {
  const negative = value !== null && value < 0;

  return (
    <div className="min-w-0">
      <dt
        className="flex items-center gap-1 truncate text-[0.6875rem] font-semibold uppercase tracking-[0.07em] text-ink-3"
        title={hint}
      >
        {label}
        {hint && <Icon name="info" size={11} className="shrink-0 opacity-60" />}
      </dt>
      <dd
        className={cx(
          'mt-1 flex items-center gap-1 text-[1.0625rem] font-semibold tabular-nums tracking-[-0.015em]',
          signed && value !== null ? (negative ? 'text-neg' : 'text-pos') : 'text-ink',
        )}
      >
        {signed && value !== null && (
          <Icon name={negative ? 'trendDown' : 'trendUp'} size={13} strokeWidth={2.1} />
        )}
        {formatFraction(value)}
      </dd>
      {caption && <p className="mt-0.5 text-[0.75rem] tabular-nums text-ink-3">{caption}</p>}
    </div>
  );
}

/** Analytics figures are computed client-side as plain fractions (never money, so `formatMoney`'s
 * decimal-string handling does not apply) — `formatPercent` still expects a decimal string, so
 * one `toFixed` conversion happens here rather than growing a second money-shaped formatter. */
function formatFraction(value: number | null): string {
  return formatPercent(value === null ? null : (value * 100).toFixed(4));
}
