import type { Transaction } from '../api/types';
import { useI18n } from '../i18n/I18nProvider';
import { formatDecimal, formatMoney } from '../lib/formatMoney';
import { Button } from '../ui/Button';
import { Icon } from '../ui/Icon';
import { Modal } from '../ui/Modal';

interface Props {
  transaction: Transaction;
  onConfirm: () => void;
  onCancel: () => void;
}

/**
 * Deleting a transaction rewrites the whole holdings projection, so the dialog names exactly
 * which one is going — type, quantity, symbol, price and date — rather than asking "are you
 * sure?" about something the user has to remember.
 *
 * <p>The delete itself is optimistic (day-4-dev-C.md D4-C3): confirming closes this dialog
 * immediately rather than waiting on the server. `PortfolioDetail` owns removing the row from the
 * list, rolling it back if the request fails, and surfacing that failure as a toast — this
 * component's job ends at "the user said yes".
 */
export function ConfirmDeleteDialog({ transaction, onConfirm, onCancel }: Props) {
  const { t } = useI18n();

  return (
    <Modal
      title={t('deleteDialog.title')}
      onClose={onCancel}
      footer={
        <>
          <Button onClick={onCancel}>{t('deleteDialog.cancel')}</Button>
          <Button variant="danger" icon="trash" onClick={onConfirm}>
            {t('deleteDialog.confirm')}
          </Button>
        </>
      }
    >
      <div className="mt-3 flex items-start gap-3 rounded-xl border border-hairline bg-surface-2 p-3">
        <span className="grid h-8 w-8 shrink-0 place-items-center rounded-lg bg-neg-soft text-neg">
          <Icon name="trash" size={15} />
        </span>
        <p className="min-w-0 text-[0.875rem] font-semibold tabular-nums text-ink" data-testid="delete-subject">
          {describe(transaction)}
        </p>
      </div>

      <p className="mt-3 text-[0.8125rem] leading-relaxed text-ink-3">{t('deleteDialog.body')}</p>
    </Modal>
  );
}

function describe(transaction: Transaction): string {
  const when = transaction.executedAt.slice(0, 10);
  if (!transaction.instrument) {
    return `${transaction.type} of ${formatMoney(transaction.price)} on ${when}`;
  }
  return `${transaction.type} ${formatDecimal(transaction.quantity, 6)} ${transaction.instrument.symbol} at ${formatMoney(transaction.price)} on ${when}`;
}
