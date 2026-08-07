import { useEffect, useRef, useState } from 'react';
import { ApiError, fieldErrors } from '../api/client';
import { createTransaction, searchInstruments } from '../api/endpoints';
import type { CurrencyCode, Instrument, Transaction, TransactionType } from '../api/types';
import { useI18n } from '../i18n/I18nProvider';
import { Button } from '../ui/Button';
import { SectionCard } from '../ui/Card';
import { Field, SelectInput, TextInput } from '../ui/Field';
import { Icon } from '../ui/Icon';

const TYPES: TransactionType[] = ['BUY', 'SELL', 'DIVIDEND', 'DEPOSIT', 'WITHDRAWAL', 'FEE'];
const CASH_TYPES: TransactionType[] = ['DEPOSIT', 'WITHDRAWAL'];
const QUANTITY_TYPES: TransactionType[] = ['BUY', 'SELL', 'DIVIDEND'];
const CURRENCIES: CurrencyCode[] = ['USD', 'EUR', 'GBP', 'INR'];

interface Props {
  portfolioId: number;
  baseCurrency: CurrencyCode;
  onAdded: (transaction: Transaction) => void;
}

/**
 * The customer's "add" (priority 3). Symbol is a type-ahead against {@code GET /instruments} so a
 * transaction can only be raised against an instrument that exists, and picking one fills in its
 * trading currency — the mismatch the API rejects with a 422 is the single easiest mistake to
 * make by hand.
 *
 * <p>Validation failures are never re-implemented here. The server owns every rule; this form
 * takes the {@code ProblemDetail} it gets back and puts each message against the input named in
 * {@code errors[]}, so a 400 from Bean Validation and a 422 from a domain rule land in exactly
 * the same place on screen.
 */
export function AddTransactionForm({ portfolioId, baseCurrency, onAdded }: Props) {
  const { t } = useI18n();

  const [type, setType] = useState<TransactionType>('BUY');
  const [symbol, setSymbol] = useState('');
  const [quantity, setQuantity] = useState('');
  const [price, setPrice] = useState('');
  const [fees, setFees] = useState('');
  const [currency, setCurrency] = useState<CurrencyCode>(baseCurrency);
  const [executedOn, setExecutedOn] = useState(todayInUtc);
  const [note, setNote] = useState('');

  const [suggestions, setSuggestions] = useState<Instrument[]>([]);
  const [showSuggestions, setShowSuggestions] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [warnings, setWarnings] = useState<string[]>([]);
  const [submitting, setSubmitting] = useState(false);

  const needsSymbol = !CASH_TYPES.includes(type);
  const needsQuantity = QUANTITY_TYPES.includes(type);
  const justPicked = useRef(false);

  useEffect(() => {
    if (!needsSymbol || symbol.trim().length < 1 || justPicked.current) {
      justPicked.current = false;
      setSuggestions([]);
      return;
    }
    const timer = setTimeout(() => {
      searchInstruments(symbol.trim())
        .then(setSuggestions)
        .catch(() => setSuggestions([]));
    }, 200);
    return () => clearTimeout(timer);
  }, [symbol, needsSymbol]);

  function pick(instrument: Instrument) {
    justPicked.current = true;
    setSymbol(instrument.symbol);
    // The API rejects a currency that is not the instrument's own with a 422; there is no reason
    // to let anyone reach that.
    setCurrency(instrument.currency);
    setShowSuggestions(false);
    setSuggestions([]);
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setErrors({});
    setFormError(null);
    setWarnings([]);

    try {
      const created = await createTransaction(portfolioId, {
        type,
        symbol: needsSymbol ? symbol.trim() : null,
        quantity: needsQuantity ? quantity : null,
        price,
        currency,
        fees: fees || null,
        executedAt: toInstant(executedOn),
        note: note || null,
      });
      setWarnings(created.warnings);
      setQuantity('');
      setPrice('');
      setFees('');
      setNote('');
      onAdded(created);
    } catch (cause) {
      if (cause instanceof ApiError) {
        const byField = fieldErrors(cause.problem);
        setErrors(byField);
        // A 404 for an unknown symbol, or any failure with no field to blame, still has to be
        // said out loud rather than swallowed.
        if (Object.keys(byField).length === 0) {
          setFormError(cause.message);
        }
      } else {
        setFormError(t('txn.unreachable'));
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <SectionCard id="add-transaction" title={t('txn.title')} icon="plus" description={t('txn.subtitle')}>
      <form onSubmit={submit} noValidate>
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <Field label={t('txn.type')} htmlFor="txn-type" error={errors.type}>
            <SelectInput
              id="txn-type"
              value={type}
              onChange={(e) => setType(e.target.value as TransactionType)}
              invalid={!!errors.type}
            >
              {TYPES.map((option) => (
                <option key={option} value={option}>
                  {option}
                </option>
              ))}
            </SelectInput>
          </Field>

          {needsSymbol && (
            <Field label={t('txn.symbol')} htmlFor="txn-symbol" error={errors.symbol}>
              <TextInput
                id="txn-symbol"
                value={symbol}
                autoComplete="off"
                placeholder={t('txn.symbolPlaceholder')}
                role="combobox"
                aria-expanded={showSuggestions && suggestions.length > 0}
                aria-autocomplete="list"
                aria-controls="txn-symbol-suggestions"
                onChange={(e) => {
                  setSymbol(e.target.value);
                  setShowSuggestions(true);
                }}
                onFocus={() => setShowSuggestions(true)}
                invalid={!!errors.symbol}
              />
              {showSuggestions && suggestions.length > 0 && (
                <ul
                  id="txn-symbol-suggestions"
                  role="listbox"
                  aria-label={t('txn.suggestions')}
                  className="absolute inset-x-0 top-full z-20 mt-1.5 max-h-64 overflow-y-auto rounded-xl border border-hairline bg-surface p-1 shadow-[var(--shadow-lg)] animate-slide-down"
                >
                  {suggestions.map((instrument) => (
                    <li key={instrument.symbol}>
                      <button
                        type="button"
                        onClick={() => pick(instrument)}
                        className="flex w-full items-baseline gap-2 rounded-lg px-2.5 py-1.5 text-start transition-colors hover:bg-surface-2"
                      >
                        <strong className="text-[0.8125rem] font-semibold text-ink">
                          {instrument.symbol}
                        </strong>
                        <span className="min-w-0 flex-1 truncate text-[0.75rem] text-ink-3">
                          {instrument.name}
                        </span>
                        <em className="shrink-0 not-italic text-[0.6875rem] font-medium text-ink-3">
                          {instrument.currency}
                        </em>
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </Field>
          )}

          {needsQuantity && (
            <Field label={t('txn.quantity')} htmlFor="txn-quantity" error={errors.quantity}>
              <TextInput
                id="txn-quantity"
                inputMode="decimal"
                value={quantity}
                onChange={(e) => setQuantity(e.target.value)}
                invalid={!!errors.quantity}
              />
            </Field>
          )}

          <Field
            label={CASH_TYPES.includes(type) ? t('txn.amount') : t('txn.price')}
            htmlFor="txn-price"
            error={errors.price}
          >
            <TextInput
              id="txn-price"
              inputMode="decimal"
              value={price}
              onChange={(e) => setPrice(e.target.value)}
              invalid={!!errors.price}
            />
          </Field>

          <Field label={t('txn.currency')} htmlFor="txn-currency" error={errors.currency}>
            <SelectInput
              id="txn-currency"
              value={currency}
              onChange={(e) => setCurrency(e.target.value as CurrencyCode)}
              invalid={!!errors.currency}
            >
              {CURRENCIES.map((option) => (
                <option key={option} value={option}>
                  {option}
                </option>
              ))}
            </SelectInput>
          </Field>

          <Field label={t('txn.fees')} htmlFor="txn-fees" error={errors.fees}>
            <TextInput
              id="txn-fees"
              inputMode="decimal"
              value={fees}
              onChange={(e) => setFees(e.target.value)}
              invalid={!!errors.fees}
            />
          </Field>

          <Field label={t('txn.date')} htmlFor="txn-executedAt" error={errors.executedAt}>
            <TextInput
              id="txn-executedAt"
              type="date"
              max={todayInUtc()}
              value={executedOn}
              onChange={(e) => setExecutedOn(e.target.value)}
              invalid={!!errors.executedAt}
            />
          </Field>

          <Field label={t('txn.note')} htmlFor="txn-note" error={errors.note} wide className="lg:col-span-full">
            <TextInput
              id="txn-note"
              value={note}
              maxLength={500}
              placeholder={t('txn.notePlaceholder')}
              onChange={(e) => setNote(e.target.value)}
              invalid={!!errors.note}
            />
          </Field>
        </div>

        {formError && (
          <p className="field-error mt-4" role="alert" data-testid="form-error">
            {formError}
          </p>
        )}

        {warnings.map((warning) => (
          <p
            key={warning}
            role="status"
            data-testid="txn-warning"
            className="mt-3 flex items-start gap-2 rounded-xl border border-[var(--warn-border)] bg-[var(--warn-soft)] px-3 py-2 text-[0.8125rem] text-[var(--warn)] animate-rise-in-sm"
          >
            <Icon name="alert" size={14} className="mt-0.5 shrink-0" />
            {warning}
          </p>
        ))}

        <div className="mt-6">
          <Button type="submit" variant="primary" icon="plus" busy={submitting}>
            {submitting ? t('txn.submitting') : t('txn.submit')}
          </Button>
        </div>
      </form>
    </SectionCard>
  );
}

function todayInUtc(): string {
  return new Date().toISOString().slice(0, 10);
}

/**
 * A date input has no time of day. Midnight UTC on the chosen day is the natural instant, except
 * that east of UTC "today" is still in the future at midnight UTC and `@PastOrPresent` would
 * reject it — so today collapses to now. The input's own `max` stops a genuinely future date.
 */
function toInstant(date: string): string {
  const midnightUtc = new Date(`${date}T00:00:00Z`);
  const now = new Date();
  return (midnightUtc > now ? now : midnightUtc).toISOString();
}
