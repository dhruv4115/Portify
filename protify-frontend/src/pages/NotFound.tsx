import { Link } from 'react-router-dom';
import { useI18n } from '../i18n/I18nProvider';
import { Page } from '../layout/AppShell';
import { Icon } from '../ui/Icon';

export function NotFound() {
  const { t } = useI18n();

  return (
    <Page>
      <div className="flex min-h-[52vh] flex-col items-center justify-center text-center animate-rise-in">
        <p
          className="bg-clip-text text-[5rem] font-bold leading-none tracking-[-0.04em] text-transparent sm:text-[7rem]"
          style={{ backgroundImage: 'var(--gradient-brand)' }}
          aria-hidden="true"
        >
          404
        </p>
        <h1 className="mt-3 text-[1.375rem] font-bold tracking-[-0.025em] text-ink">{t('notFound.title')}</h1>
        <p className="mt-2 max-w-sm text-[0.875rem] text-ink-3">{t('notFound.body')}</p>
        <Link to="/" className="btn btn-primary mt-6">
          <Icon name="arrowLeft" size={15} className="rtl-flip" />
          {t('notFound.home')}
        </Link>
      </div>
    </Page>
  );
}
