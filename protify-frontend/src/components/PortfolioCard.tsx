import { Link } from 'react-router-dom';
import type { Portfolio } from '../api/types';
import { useI18n } from '../i18n/I18nProvider';
import { formatMoney } from '../lib/formatMoney';
import { Icon } from '../ui/Icon';
import { Delta } from '../ui/Stat';
import { cx } from '../ui/cx';
import { StaleBadge } from './StaleBadge';

/**
 * One portfolio, as a card. Shared by the overview and the portfolio list so the two pages cannot
 * drift into showing the same object two different ways.
 *
 * <p>The whole card is the link rather than the title alone: a 260px target beats a 120px one on
 * a phone, and there is nothing else inside it competing for the click.
 */
export function PortfolioCard({ portfolio, index = 0 }: { portfolio: Portfolio; index?: number }) {
  const { t } = useI18n();
  const holdings =
    portfolio.holdingCount === 1
      ? t('portfolios.holdingsOne')
      : t('portfolios.holdings', { count: portfolio.holdingCount });

  return (
    <Link
      to={`/portfolios/${portfolio.id}`}
      className={cx(
        'card card-sheen card-interactive group flex flex-col gap-3 animate-rise-in',
        `stagger-${Math.min(index, 11)}`,
      )}
    >
      <div className="flex items-start justify-between gap-2">
        <h3 className="truncate text-[0.9375rem] font-semibold tracking-[-0.01em] text-ink">
          {portfolio.name}
        </h3>
        <span className="badge shrink-0">{portfolio.baseCurrency}</span>
      </div>

      <div>
        <p className="flex flex-wrap items-center gap-2 text-[1.5rem] font-semibold tracking-[-0.025em] tabular-nums text-ink">
          {formatMoney(portfolio.totalValue)}
          <StaleBadge dataQuality={portfolio.dataQuality} />
        </p>
        <Delta
          money={portfolio.unrealisedPnl}
          percent={portfolio.unrealisedPnlPct}
          className="mt-1"
        />
      </div>

      <div className="mt-auto flex items-center justify-between border-t border-hairline-subtle pt-3 text-[0.75rem] text-ink-3">
        <span>{holdings}</span>
        <span className="inline-flex items-center gap-1 text-brand-text opacity-0 transition-opacity duration-200 group-hover:opacity-100 group-focus-visible:opacity-100">
          <Icon name="arrowRight" size={13} className="rtl-flip" />
        </span>
      </div>
    </Link>
  );
}

/** The list-view row. Same data, denser — for comparing many portfolios rather than reading one. */
export function PortfolioRow({ portfolio, index = 0 }: { portfolio: Portfolio; index?: number }) {
  const { t } = useI18n();
  const holdings =
    portfolio.holdingCount === 1
      ? t('portfolios.holdingsOne')
      : t('portfolios.holdings', { count: portfolio.holdingCount });

  return (
    <Link
      to={`/portfolios/${portfolio.id}`}
      className={cx(
        'group flex items-center gap-4 rounded-xl border border-hairline bg-surface px-4 py-3 transition-all duration-200 hover:border-hairline-strong hover:shadow-[var(--shadow-md)] animate-rise-in-sm',
        `stagger-${Math.min(index, 11)}`,
      )}
    >
      <span className="grid h-9 w-9 shrink-0 place-items-center rounded-lg bg-brand-soft text-brand-text">
        <Icon name="wallet" size={16} />
      </span>

      <span className="min-w-0 flex-1">
        <span className="flex items-center gap-2">
          <span className="truncate text-[0.875rem] font-semibold text-ink">{portfolio.name}</span>
          <StaleBadge dataQuality={portfolio.dataQuality} />
        </span>
        <span className="block text-[0.75rem] text-ink-3">
          {holdings} · {portfolio.baseCurrency}
        </span>
      </span>

      <span className="hidden text-end sm:block">
        <span className="block text-[0.9375rem] font-semibold tabular-nums text-ink">
          {formatMoney(portfolio.totalValue)}
        </span>
        <Delta money={portfolio.unrealisedPnl} percent={portfolio.unrealisedPnlPct} size="sm" />
      </span>

      <Icon
        name="chevronRight"
        size={16}
        className="rtl-flip shrink-0 text-ink-3 transition-transform duration-200 group-hover:translate-x-0.5"
      />
    </Link>
  );
}
