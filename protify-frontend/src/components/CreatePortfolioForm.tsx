import { useState } from 'react';
import { ApiError, fieldErrors } from '../api/client';
import { createPortfolio } from '../api/endpoints';
import type { CurrencyCode, Portfolio } from '../api/types';
import { useI18n } from '../i18n/I18nProvider';
import { Button } from '../ui/Button';
import { SectionCard } from '../ui/Card';
import { Field, SelectInput, TextInput } from '../ui/Field';

const CURRENCIES: CurrencyCode[] = ['USD', 'EUR', 'GBP', 'INR'];

interface Props {
  onCreated: (portfolio: Portfolio) => void;
  onCancel: () => void;
}

/**
 * The entry point to everything else: with no portfolio there is nothing to add a transaction to,
 * so this is the one screen a new account must be able to reach.
 *
 * <p>Validation follows {@link AddTransactionForm} — the server owns every rule and its
 * {@code ProblemDetail} is put against the input named in {@code errors[]}. The duplicate-name
 * 409 carries no {@code errors[]}, so it lands in the form-level message rather than being
 * silently dropped.
 */
export function CreatePortfolioForm({ onCreated, onCancel }: Props) {
  const { t } = useI18n();
  const [name, setName] = useState('');
  const [baseCurrency, setBaseCurrency] = useState<CurrencyCode>('USD');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setErrors({});
    setFormError(null);

    try {
      const created = await createPortfolio({ name: name.trim(), baseCurrency });
      onCreated(created);
    } catch (cause) {
      if (cause instanceof ApiError) {
        const byField = fieldErrors(cause.problem);
        setErrors(byField);
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
    <SectionCard
      title={t('createPortfolio.title')}
      icon="plus"
      description={t('createPortfolio.subtitle')}
    >
      <form onSubmit={submit} noValidate>
        <div className="grid gap-4 sm:grid-cols-[2fr_1fr]">
          <Field label={t('createPortfolio.name')} htmlFor="portfolio-name" error={errors.name}>
            <TextInput
              id="portfolio-name"
              value={name}
              maxLength={120}
              autoFocus
              placeholder={t('createPortfolio.namePlaceholder')}
              onChange={(event) => setName(event.target.value)}
              invalid={!!errors.name}
            />
          </Field>

          <Field
            label={t('createPortfolio.baseCurrency')}
            htmlFor="portfolio-currency"
            error={errors.baseCurrency}
            hint={t('createPortfolio.baseCurrencyHint')}
          >
            <SelectInput
              id="portfolio-currency"
              value={baseCurrency}
              onChange={(event) => setBaseCurrency(event.target.value as CurrencyCode)}
              invalid={!!errors.baseCurrency}
            >
              {CURRENCIES.map((code) => (
                <option key={code} value={code}>
                  {code}
                </option>
              ))}
            </SelectInput>
          </Field>
        </div>

        {formError && (
          <p className="field-error mt-4" role="alert" data-testid="form-error">
            {formError}
          </p>
        )}

        <div className="mt-6 flex flex-wrap gap-2">
          <Button
            type="submit"
            variant="primary"
            busy={submitting}
            disabled={submitting || !name.trim()}
          >
            {submitting ? t('createPortfolio.submitting') : t('createPortfolio.submit')}
          </Button>
          <Button onClick={onCancel} disabled={submitting}>
            {t('common.cancel')}
          </Button>
        </div>
      </form>
    </SectionCard>
  );
}
