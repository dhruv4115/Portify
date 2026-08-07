import { Link } from 'react-router-dom';
import { useI18n } from '../i18n/I18nProvider';
import { Icon } from '../ui/Icon';
import { LanguageSwitcher } from './LanguageSwitcher';
import { Logo, Wordmark } from './Logo';
import { ThemeChoiceMenu } from './ThemeToggle';

/**
 * The footer.
 *
 * <p>It carries the preference controls a second time on purpose. Someone who reaches the bottom
 * of a page looking for the language or theme switch should find it there rather than having to
 * scroll back up — duplicating two controls is cheaper than making people hunt.
 */
export function Footer() {
  const { t } = useI18n();

  return (
    <footer className="mt-auto border-t border-hairline bg-[var(--plane-accent)]">
      <div className="mx-auto max-w-[84rem] px-4 py-10 sm:px-6">
        <div className="grid gap-8 sm:grid-cols-2 lg:grid-cols-[1.4fr_1fr_1fr_1.2fr]">
          <div>
            <div className="flex items-center gap-2.5">
              <Logo size={26} />
              <Wordmark />
            </div>
            <p className="mt-3 max-w-xs text-[0.8125rem] leading-relaxed text-ink-3">
              {t('app.description')}
            </p>
            <p className="mt-4 inline-flex items-center gap-1.5 text-[0.75rem] font-medium text-pos">
              <span className="relative flex h-1.5 w-1.5">
                <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-pos opacity-60" />
                <span className="relative inline-flex h-1.5 w-1.5 rounded-full bg-pos" />
              </span>
              {t('footer.status')}
            </p>
          </div>

          <FooterColumn title={t('footer.product')}>
            <FooterLink to="/">{t('nav.overview')}</FooterLink>
            <FooterLink to="/portfolios">{t('nav.portfolios')}</FooterLink>
            <FooterLink to="/settings">{t('nav.settings')}</FooterLink>
          </FooterColumn>

          <FooterColumn title={t('footer.resources')}>
            <FooterLink to="/about">{t('nav.about')}</FooterLink>
            <FooterLink to="/about#shortcuts">{t('about.shortcuts')}</FooterLink>
            <li>
              <a
                href="https://github.com/Neueda-Learning/11_105_portfoliomanagement_Portify"
                target="_blank"
                rel="noreferrer noopener"
                className="link-quiet inline-flex items-center gap-1 text-[0.8125rem]"
              >
                {t('footer.apiDocs')}
                <Icon name="external" size={12} />
              </a>
            </li>
          </FooterColumn>

          <div>
            <h2 className="text-[0.6875rem] font-semibold uppercase tracking-[0.07em] text-ink-3">
              {t('footer.preferences')}
            </h2>
            <div className="mt-3 flex flex-wrap items-center gap-1">
              <LanguageSwitcher />
              <ThemeChoiceMenu />
            </div>
            <p className="mt-3 text-[0.75rem] leading-relaxed text-ink-3">{t('language.hint')}</p>
          </div>
        </div>

        <div className="mt-9 border-t border-hairline pt-5">
          <p className="text-[0.75rem] text-ink-3">
            {t('footer.rights', { year: new Date().getFullYear() })}
          </p>
        </div>
      </div>
    </footer>
  );
}

function FooterColumn({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <h2 className="text-[0.6875rem] font-semibold uppercase tracking-[0.07em] text-ink-3">{title}</h2>
      <ul className="mt-3 space-y-2">{children}</ul>
    </div>
  );
}

function FooterLink({ to, children }: { to: string; children: React.ReactNode }) {
  return (
    <li>
      <Link to={to} className="link-quiet text-[0.8125rem]">
        {children}
      </Link>
    </li>
  );
}
