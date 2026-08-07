import { useMemo, useState } from 'react';
import type { Transaction, TransactionType } from '../api/types';
import { useI18n } from '../i18n/I18nProvider';
import { formatDecimal, formatMoney } from '../lib/formatMoney';
import { formatFullDate } from '../lib/ranges';
import { Button, IconButton } from '../ui/Button';
import { SectionCard } from '../ui/Card';
import { EmptyState, SkeletonRows } from '../ui/Feedback';
import { SearchInput, SelectInput } from '../ui/Field';
import { cx } from '../ui/cx';

const TYPES: TransactionType[] = ['BUY', 'SELL', 'DIVIDEND', 'DEPOSIT', 'WITHDRAWAL', 'FEE'];

interface Props {
  transactions: Transaction[];
  loading?: boolean;
  onDelete: (transaction: Transaction) => void;
  /** Total the server holds, when more pages exist than are on screen. */
  total?: number;
  hasMore?: boolean;
  loadingMore?: boolean;
  onLoadMore?: () => void;
}

/**
 * The list the customer's "remove" acts on. `totalBase` sits next to `totalNative` because that
 * pair, plus `fxRateApplied`, is the multi-currency story made visible: the rate shown is the one
 * on the transaction's own date, not today's.
 *
 * <p>Filtering is client-side over the page already loaded, and "load more" appends the next page
 * rather than replacing it — so narrowing to `SELL` never silently hides sells that are one page
 * further back. The counter under the table says how many of the total are actually in hand,
 * because a filter over a partial list would otherwise look like a complete answer.
 */
export function TransactionList({
  transactions,
  loading = false,
  onDelete,
  total,
  hasMore,
  loadingMore,
  onLoadMore,
}: Props) {
  const { t, meta } = useI18n();
  const [query, setQuery] = useState('');
  const [type, setType] = useState<TransactionType | 'ALL'>('ALL');

  const visible = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return transactions.filter((transaction) => {
      if (type !== 'ALL' && transaction.type !== type) {
        return false;
      }
      if (!needle) {
        return true;
      }
      return (
        transaction.instrument?.symbol.toLowerCase().includes(needle) ||
        transaction.instrument?.name.toLowerCase().includes(needle) ||
        transaction.note?.toLowerCase().includes(needle) ||
        transaction.type.toLowerCase().includes(needle)
      );
    });
  }, [transactions, query, type]);

  if (loading) {
    return (
      <SectionCard id="transactions" title={t('transactions.title')} icon="clock" busy>
        <SkeletonRows rows={4} testId="transactions-skeleton" />
      </SectionCard>
    );
  }

  if (transactions.length === 0) {
    return (
      <SectionCard id="transactions" title={t('transactions.title')} icon="clock">
        <EmptyState icon="clock" title={t('transactions.empty')} />
      </SectionCard>
    );
  }

  return (
    <SectionCard
      id="transactions"
      title={t('transactions.title')}
      icon="clock"
      actions={
        <>
          <SearchInput
            id="transactions-search"
            value={query}
            onValueChange={setQuery}
            label={t('transactions.search')}
            clearLabel={t('portfolios.clearSearch')}
            placeholder={t('transactions.searchPlaceholder')}
            className="w-full sm:w-56"
          />
          <SelectInput
            aria-label={t('transactions.filterType')}
            value={type}
            onChange={(event) => setType(event.target.value as TransactionType | 'ALL')}
            className="h-9 w-auto py-0 text-[0.8125rem]"
          >
            <option value="ALL">{t('transactions.allTypes')}</option>
            {TYPES.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </SelectInput>
        </>
      }
      footer={
        <div className="flex flex-wrap items-center justify-between gap-3">
          <p className="text-[0.75rem] text-ink-3">
            {t('transactions.showing', { shown: visible.length, total: total ?? transactions.length })}
          </p>
          {hasMore && onLoadMore && (
            <Button size="sm" icon="chevronDown" busy={loadingMore} onClick={onLoadMore}>
              {loadingMore ? t('transactions.loadingMore') : t('transactions.loadMore')}
            </Button>
          )}
        </div>
      }
    >
      {visible.length === 0 ? (
        <EmptyState
          icon="filter"
          inset
          title={t('transactions.noMatches')}
          action={
            <Button
              icon="x"
              onClick={() => {
                setQuery('');
                setType('ALL');
              }}
            >
              {t('transactions.clearFilters')}
            </Button>
          }
        />
      ) : (
        <div className="table-scroll">
          <table className="table" data-testid="transactions-table">
            <thead>
              <tr>
                <th scope="col">{t('transactions.date')}</th>
                <th scope="col">{t('transactions.type')}</th>
                <th scope="col">{t('transactions.instrument')}</th>
                <th scope="col" className="numeric">
                  {t('transactions.quantity')}
                </th>
                <th scope="col" className="numeric">
                  {t('transactions.price')}
                  <span className="block text-[0.625rem] font-normal normal-case tracking-normal opacity-60">
                    {t('holdings.native')}
                  </span>
                </th>
                <th scope="col" className="numeric">
                  {t('transactions.total')}
                  <span className="block text-[0.625rem] font-normal normal-case tracking-normal opacity-60">
                    {t('holdings.base')}
                  </span>
                </th>
                <th scope="col" className="numeric">
                  {t('transactions.fxOnDay')}
                </th>
                <th scope="col">
                  <span className="visually-hidden">{t('transactions.actions')}</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {visible.map((transaction, index) => {
                const date = transaction.executedAt.slice(0, 10);
                return (
                  <tr
                    key={transaction.id}
                    className={cx('group', `animate-rise-in-sm stagger-${Math.min(index, 11)}`)}
                  >
                    <td className="whitespace-nowrap text-ink-2">{formatFullDate(date, meta.locale)}</td>
                    <td>
                      <span className={`pill pill-${transaction.type.toLowerCase()}`}>{transaction.type}</span>
                    </td>
                    <td className="font-medium">
                      {transaction.instrument?.symbol ?? (
                        <span className="text-ink-3">{t('transactions.cash')}</span>
                      )}
                    </td>
                    <td className="numeric">{formatDecimal(transaction.quantity, 6)}</td>
                    <td className="numeric">{formatMoney(transaction.price)}</td>
                    <td className="numeric font-medium">
                      {formatMoney(transaction.totalBase ?? transaction.totalNative)}
                    </td>
                    <td className="numeric text-ink-2">
                      {transaction.fxRateApplied ? (
                        formatDecimal(transaction.fxRateApplied, 4)
                      ) : (
                        <span className="text-ink-3">—</span>
                      )}
                    </td>
                    <td>
                      {/* Revealed on hover only where hovering exists. On touch there is no
                          hover state, so the control stays visible rather than unreachable. */}
                      <span className="flex justify-end transition-opacity duration-150 focus-within:opacity-100 md:opacity-0 md:group-hover:opacity-100">
                        <IconButton
                          icon="trash"
                          size={15}
                          label={t('transactions.deleteLabel', { type: transaction.type, date })}
                          onClick={() => onDelete(transaction)}
                          className="h-8 w-8 hover:!text-neg"
                        />
                      </span>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </SectionCard>
  );
}
