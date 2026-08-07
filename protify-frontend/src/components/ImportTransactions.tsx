import { useRef, useState } from 'react';
import { importTransactions } from '../api/endpoints';
import type { ImportResult } from '../api/types';
import { useI18n } from '../i18n/I18nProvider';
import { Button } from '../ui/Button';
import { Icon } from '../ui/Icon';
import { toastFromError, useToast } from './Toast';

interface Props {
  portfolioId: number;
  /** Called after a real import that wrote something. The parent refetches everything: an import
   * changes holdings, the chart, the allocation and the ledger, not just the list below it. */
  onImported: () => void;
}

/**
 * The other half of the export card: a CSV of transactions goes back in.
 *
 * <p><b>Two steps, not one.</b> Choosing a file runs a dry run — the server parses, validates and
 * projects the whole batch inside a transaction it then rolls back — and only the button under the
 * result actually writes. An import is a bulk write to a financial ledger; the cost of a second
 * click is nothing against uploading the wrong file and having to unpick thirty transactions by
 * hand. It also means the errors below are the real server's errors, not a second, weaker copy of
 * its rules re-implemented here in TypeScript, which would eventually disagree with it.
 *
 * <p>There is no holdings import to pair with the holdings export, and the empty state says so.
 * Holdings are computed from the ledger; importing transactions is what moves them.
 */
export function ImportTransactions({ portfolioId, onImported }: Props) {
  const { t } = useI18n();
  const { showToast } = useToast();
  const inputRef = useRef<HTMLInputElement>(null);

  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<ImportResult | null>(null);
  const [checking, setChecking] = useState(false);
  const [importing, setImporting] = useState(false);

  function reset() {
    setFile(null);
    setPreview(null);
    // The input keeps its value after a change event, so re-picking the same file after fixing it
    // would fire no event at all and look like a dead button.
    if (inputRef.current) {
      inputRef.current.value = '';
    }
  }

  async function choose(chosen: File | undefined) {
    if (!chosen) {
      return;
    }
    setFile(chosen);
    setPreview(null);
    setChecking(true);
    try {
      setPreview(await importTransactions(portfolioId, chosen, { dryRun: true }));
    } catch (cause) {
      showToast(toastFromError(cause, t('import.failed')));
      reset();
    } finally {
      setChecking(false);
    }
  }

  async function confirm() {
    if (!file) {
      return;
    }
    setImporting(true);
    try {
      const result = await importTransactions(portfolioId, file);
      if (result.imported > 0) {
        showToast({
          variant: 'success',
          title: t('import.done', { count: result.imported }),
          detail: result.warnings[0],
        });
        reset();
        onImported();
      } else {
        // The dry run passed and the real import did not: the ledger changed underneath it, or a
        // rule that depends on history now rejects a row. Show the fresh errors rather than the
        // stale preview.
        setPreview(result);
      }
    } catch (cause) {
      showToast(toastFromError(cause, t('import.failed')));
    } finally {
      setImporting(false);
    }
  }

  const rejected = preview !== null && preview.errors.length > 0;
  const ready = preview !== null && !rejected && preview.imported > 0;

  return (
    <div className="card card-sheen">
      <div className="flex flex-wrap items-center gap-4">
        <span className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-brand-soft text-brand-text">
          <Icon name="upload" size={18} />
        </span>
        <div className="min-w-0 flex-1">
          <h2 className="card-title">{t('import.title')}</h2>
          <p className="mt-0.5 text-[0.8125rem] text-ink-3">{t('import.subtitle')}</p>
        </div>
        <div className="flex flex-wrap gap-2">
          <input
            ref={inputRef}
            type="file"
            accept=".csv,text/csv"
            className="sr-only"
            aria-label={t('import.choose')}
            onChange={(event) => void choose(event.target.files?.[0])}
          />
          <Button
            icon="upload"
            busy={checking}
            onClick={() => inputRef.current?.click()}
          >
            {checking ? t('import.checking') : t('import.choose')}
          </Button>
          {file && !checking && (
            <Button variant="ghost" icon="x" onClick={reset}>
              {t('import.clear')}
            </Button>
          )}
        </div>
      </div>

      {file && preview && (
        <div className="mt-4 border-t border-hairline pt-4" data-testid="import-preview">
          <p className="text-[0.8125rem] text-ink-2">
            <strong className="font-semibold text-ink">{file.name}</strong>{' '}
            {t('import.rowsRead', { count: preview.totalRows })}
          </p>

          {rejected ? (
            <>
              <p className="field-error mt-2" role="alert">
                {t('import.rejected', { count: preview.errors.length })}
              </p>
              <ul className="mt-2 max-h-56 space-y-1 overflow-y-auto text-[0.8125rem] text-ink-2">
                {preview.errors.map((error) => (
                  <li key={`${error.line}-${error.message}`} className="flex gap-2">
                    <span className="shrink-0 font-medium text-ink-3">
                      {t('import.line', { line: error.line })}
                    </span>
                    <span className="min-w-0">{error.message}</span>
                  </li>
                ))}
              </ul>
            </>
          ) : (
            <>
              <p className="mt-2 flex items-center gap-2 text-[0.8125rem] text-pos">
                <Icon name="check" size={14} />
                {t('import.valid', { count: preview.imported })}
              </p>
              {preview.warnings.map((warning) => (
                <p
                  key={warning}
                  role="status"
                  data-testid="import-warning"
                  className="mt-2 flex items-start gap-2 rounded-xl border border-[var(--warn-border)] bg-[var(--warn-soft)] px-3 py-2 text-[0.8125rem] text-[var(--warn)]"
                >
                  <Icon name="alert" size={14} className="mt-0.5 shrink-0" />
                  {warning}
                </p>
              ))}
            </>
          )}

          {ready && (
            <div className="mt-4">
              <Button variant="primary" icon="check" busy={importing} onClick={() => void confirm()}>
                {importing ? t('import.importing') : t('import.confirm', { count: preview.imported })}
              </Button>
            </div>
          )}
        </div>
      )}

      <p className="mt-4 text-[0.75rem] leading-relaxed text-ink-3">{t('import.format')}</p>
    </div>
  );
}
