import { useState } from 'react';
import { getAllTransactions } from '../api/endpoints';
import type { Holding, Transaction } from '../api/types';
import { useI18n } from '../i18n/I18nProvider';
import { downloadCsv, toCsv } from '../lib/csv';
import { Button } from '../ui/Button';
import { Icon } from '../ui/Icon';
import { toastFromError, useToast } from './Toast';

interface Props {
  portfolioId: number;
  holdings: Holding[];
}

/** day-5-dev-C.md D5-C2: "half an hour, and demos well." Built entirely from data the app
 * already has (holdings) or one extra paginated fetch (every transaction, not just the visible
 * page) — no server-side export endpoint needed. */
export function ExportButtons({ portfolioId, holdings }: Props) {
  const { t } = useI18n();
  const { showToast } = useToast();
  const [exportingTransactions, setExportingTransactions] = useState(false);

  function exportHoldings() {
    const rows = holdings.map((holding) => [
      holding.instrument.symbol,
      holding.instrument.name,
      holding.quantity,
      holding.avgCost.amount,
      holding.avgCost.currency,
      holding.lastPrice?.amount ?? '',
      holding.marketValue?.amount ?? '',
      holding.marketValue?.currency ?? '',
      holding.unrealisedPnl?.amount ?? '',
      holding.realisedPnl.amount,
      holding.weightPct,
    ]);
    const csv = toCsv(
      ['Symbol', 'Name', 'Quantity', 'Avg cost', 'Avg cost currency', 'Last price', 'Market value',
        'Market value currency', 'Unrealised P&L', 'Realised P&L', 'Weight %'],
      rows,
    );
    downloadCsv(`portfolio-${portfolioId}-holdings.csv`, csv);
  }

  /**
   * The one export that can fail: it fetches, and a fetch has a server on the other end.
   *
   * <p>The failure is toasted rather than left to reject into nothing. Without the `catch` this
   * button was indistinguishable from a broken one — a rejected promise handed to `void` prints
   * to the console and stops there, so a 400 from the paging parameters (which is exactly what a
   * stale build asking for `size=200` gets, against a `@Max(100)` server) looked to the user like
   * a click that did nothing at all, while the holdings button next to it — which fetches
   * nothing — kept working.
   */
  async function exportTransactions() {
    setExportingTransactions(true);
    try {
      const transactions = await getAllTransactions(portfolioId);
      const csv = toCsv(
        ['Date', 'Type', 'Symbol', 'Quantity', 'Price', 'Currency', 'Fees', 'Total (native)', 'Total (base)', 'Note'],
        transactions.map(toRow),
      );
      downloadCsv(`portfolio-${portfolioId}-transactions.csv`, csv);
    } catch (cause) {
      showToast(toastFromError(cause, t('export.failed')));
    } finally {
      setExportingTransactions(false);
    }
  }

  return (
    <div className="card card-sheen flex flex-wrap items-center gap-4">
      <span className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-brand-soft text-brand-text">
        <Icon name="download" size={18} />
      </span>
      <div className="min-w-0 flex-1">
        <h2 className="card-title">{t('export.title')}</h2>
        <p className="mt-0.5 text-[0.8125rem] text-ink-3">{t('export.subtitle')}</p>
      </div>
      <div className="export-buttons flex flex-wrap gap-2">
        <Button icon="download" onClick={exportHoldings} disabled={holdings.length === 0}>
          {t('export.holdings')}
        </Button>
        <Button
          icon="download"
          busy={exportingTransactions}
          onClick={() => void exportTransactions()}
        >
          {exportingTransactions ? t('export.exporting') : t('export.transactions')}
        </Button>
      </div>
    </div>
  );
}

function toRow(txn: Transaction): (string | number)[] {
  return [
    txn.executedAt,
    txn.type,
    txn.instrument?.symbol ?? '',
    txn.quantity,
    txn.price.amount,
    txn.price.currency,
    txn.fees.amount,
    txn.totalNative.amount,
    txn.totalBase?.amount ?? '',
    txn.note ?? '',
  ];
}
