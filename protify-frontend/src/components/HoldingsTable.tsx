import { useMemo, useState } from 'react';
import type { Holding } from '../api/types';
import { useI18n } from '../i18n/I18nProvider';
import { formatDecimal, formatMoney, formatPercent, isNegative } from '../lib/formatMoney';
import { compareAmounts } from '../lib/money';
import { Button } from '../ui/Button';
import { SectionCard } from '../ui/Card';
import { EmptyState, SkeletonRows } from '../ui/Feedback';
import { SearchInput } from '../ui/Field';
import { Icon } from '../ui/Icon';
import { cx } from '../ui/cx';
import { StaleBadge } from './StaleBadge';

interface Props {
  holdings: Holding[];
  loading?: boolean;
}

type SortKey = 'symbol' | 'quantity' | 'value' | 'pnl' | 'weight';
type Direction = 'asc' | 'desc';

/**
 * The customer's "browse". The column order carries the currency story that the API is built
 * around (§0.3): <b>avg cost</b> and <b>last price</b> are shown in each instrument's own
 * currency — dollars next to rupees next to pounds, in the same table — while <b>value</b>,
 * <b>P&amp;L</b> and <b>weight</b> are all in the portfolio's base currency. Each money cell is
 * labelled by the server's own `currency` field, so a mistake here would be visible rather than
 * silent.
 *
 * <p>Sorting and filtering happen on the rows already in hand. Every holding is fetched in one
 * request, so paging this through the server would add a round trip to answer a question the
 * browser can already answer instantly.
 */
export function HoldingsTable({ holdings, loading = false }: Props) {
  const { t } = useI18n();
  const [query, setQuery] = useState('');
  const [sort, setSort] = useState<SortKey>('weight');
  const [direction, setDirection] = useState<Direction>('desc');

  function toggleSort(key: SortKey) {
    if (key === sort) {
      setDirection((current) => (current === 'asc' ? 'desc' : 'asc'));
    } else {
      setSort(key);
      // Text reads naturally A→Z; figures are almost always wanted largest-first.
      setDirection(key === 'symbol' ? 'asc' : 'desc');
    }
  }

  const visible = useMemo(() => {
    const needle = query.trim().toLowerCase();
    const filtered = needle
      ? holdings.filter(
          (holding) =>
            holding.instrument.symbol.toLowerCase().includes(needle) ||
            holding.instrument.name.toLowerCase().includes(needle),
        )
      : holdings;

    const sorted = [...filtered].sort((left, right) => {
      switch (sort) {
        case 'symbol':
          return left.instrument.symbol.localeCompare(right.instrument.symbol);
        case 'quantity':
          return compareAmounts(left.quantity, right.quantity);
        // An unpriced position has no value to rank; it sorts to the bottom either way.
        case 'value':
          return compareAmounts(left.marketValue?.amount ?? '0', right.marketValue?.amount ?? '0');
        case 'pnl':
          return compareAmounts(left.unrealisedPnl?.amount ?? '0', right.unrealisedPnl?.amount ?? '0');
        case 'weight':
          return compareAmounts(left.weightPct, right.weightPct);
      }
    });

    return direction === 'desc' ? sorted.reverse() : sorted;
  }, [holdings, query, sort, direction]);

  if (loading) {
    return (
      <SectionCard id="holdings" title={t('holdings.title')} icon="layers" busy>
        <SkeletonRows rows={4} testId="holdings-skeleton" />
      </SectionCard>
    );
  }

  if (holdings.length === 0) {
    return (
      <SectionCard id="holdings" title={t('holdings.title')} icon="layers">
        <EmptyState icon="layers" testId="holdings-empty" title={t('holdings.empty')} />
      </SectionCard>
    );
  }

  const columns: { key: SortKey; label: string; unit?: string; numeric: boolean }[] = [
    { key: 'symbol', label: t('holdings.symbol'), numeric: false },
    { key: 'quantity', label: t('holdings.quantity'), numeric: true },
    { key: 'value', label: t('holdings.value'), unit: t('holdings.base'), numeric: true },
    { key: 'pnl', label: t('holdings.pnl'), unit: t('holdings.base'), numeric: true },
    { key: 'weight', label: t('holdings.weight'), numeric: true },
  ];

  return (
    <SectionCard
      id="holdings"
      title={t('holdings.title')}
      icon="layers"
      actions={
        <>
          <span className="badge">
            {holdings.length === 1 ? t('holdings.countOne') : t('holdings.count', { count: holdings.length })}
          </span>
          {holdings.length > 4 && (
            <SearchInput
              id="holdings-search"
              value={query}
              onValueChange={setQuery}
              label={t('holdings.search')}
              clearLabel={t('portfolios.clearSearch')}
              placeholder={t('holdings.searchPlaceholder')}
              className="w-full sm:w-56"
            />
          )}
        </>
      }
    >
      {visible.length === 0 ? (
        <EmptyState
          icon="search"
          inset
          title={t('holdings.noMatches', { query })}
          action={
            <Button onClick={() => setQuery('')} icon="x">
              {t('portfolios.clearSearch')}
            </Button>
          }
        />
      ) : (
        <div className="table-scroll">
          <table className="table" data-testid="holdings-table">
            <thead>
              <tr>
                {columns.map((column) => (
                  <th
                    key={column.key}
                    scope="col"
                    className={cx(column.numeric && 'numeric')}
                    aria-sort={sort === column.key ? (direction === 'asc' ? 'ascending' : 'descending') : 'none'}
                  >
                    <button
                      type="button"
                      className="th-sort"
                      data-active={sort === column.key}
                      data-direction={direction}
                      onClick={() => toggleSort(column.key)}
                    >
                      {column.label}
                      <Icon name={direction === 'asc' ? 'arrowUp' : 'arrowDown'} size={11} strokeWidth={2.4} />
                    </button>
                    {column.unit && (
                      <span className="block text-[0.625rem] font-normal normal-case tracking-normal opacity-60">
                        {column.unit}
                      </span>
                    )}
                  </th>
                ))}
                {/* Native-currency columns are informational rather than comparable across rows,
                    so they are not offered as sort keys. */}
                <th scope="col" className="numeric">
                  {t('holdings.avgCost')}
                  <span className="block text-[0.625rem] font-normal normal-case tracking-normal opacity-60">
                    {t('holdings.native')}
                  </span>
                </th>
                <th scope="col" className="numeric">
                  {t('holdings.lastPrice')}
                  <span className="block text-[0.625rem] font-normal normal-case tracking-normal opacity-60">
                    {t('holdings.native')}
                  </span>
                </th>
              </tr>
            </thead>
            <tbody>
              {visible.map((holding, index) => (
                <tr key={holding.instrument.symbol} className={`animate-rise-in-sm stagger-${Math.min(index, 11)}`}>
                  <th scope="row" className="font-normal">
                    <span className="flex flex-col gap-0.5">
                      <span className="flex items-center gap-1.5">
                        <span className="font-semibold text-ink">{holding.instrument.symbol}</span>
                        <StaleBadge dataQuality={holding.dataQuality} />
                      </span>
                      <span className="truncate text-[0.75rem] text-ink-3">{holding.instrument.name}</span>
                    </span>
                  </th>
                  <td className="numeric">{formatDecimal(holding.quantity, 6)}</td>
                  <td className="numeric font-medium">{formatMoney(holding.marketValue)}</td>
                  <td className={cx('numeric', isNegative(holding.unrealisedPnl) ? 'text-neg' : 'text-pos')}>
                    <span className="font-semibold">{formatMoney(holding.unrealisedPnl)}</span>
                    <span className="block text-[0.75rem] opacity-80">
                      {formatPercent(holding.unrealisedPnlPct)}
                    </span>
                  </td>
                  <td className="numeric">
                    <span className="flex items-center justify-end gap-2">
                      {/* A bar as well as the number: relative size is what a weight column is
                          actually asked, and a 2px rule answers it without a second chart. */}
                      <span className="hidden h-1 w-12 overflow-hidden rounded-full bg-surface-3 sm:block">
                        <span
                          className="block h-full rounded-full bg-brand"
                          style={{ width: `${Math.min(Number(holding.weightPct), 100)}%` }}
                        />
                      </span>
                      {formatPercent(holding.weightPct)}
                    </span>
                  </td>
                  <td className="numeric text-ink-2">{formatMoney(holding.avgCost)}</td>
                  <td className="numeric text-ink-2">{formatMoney(holding.lastPrice)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </SectionCard>
  );
}
