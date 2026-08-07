import { useEffect, useState } from 'react';
import { Link, NavLink, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { useI18n } from '../i18n/I18nProvider';
import { Icon, type IconName } from '../ui/Icon';
import { Menu, MenuItem } from '../ui/Menu';
import { cx } from '../ui/cx';
import { LanguageSwitcher } from './LanguageSwitcher';
import { Logo, Wordmark } from './Logo';
import { ThemeToggle } from './ThemeToggle';

interface NavItem {
  to: string;
  labelKey: 'nav.overview' | 'nav.portfolios' | 'nav.settings' | 'nav.about';
  icon: IconName;
  /** `/` would otherwise match every route. */
  end?: boolean;
}

const NAV: NavItem[] = [
  { to: '/', labelKey: 'nav.overview', icon: 'activity', end: true },
  { to: '/portfolios', labelKey: 'nav.portfolios', icon: 'layers' },
  { to: '/settings', labelKey: 'nav.settings', icon: 'settings' },
  { to: '/about', labelKey: 'nav.about', icon: 'info' },
];

/**
 * The application header: identity on one side, the tools that apply everywhere on the other.
 *
 * <p>It is glass rather than solid so content scrolling beneath it stays faintly visible, which
 * keeps the page feeling continuous instead of clipped. The border only appears once the page has
 * actually scrolled — a hairline under a header at scroll-top is a line with nothing above it.
 */
export function Header({ onOpenPalette }: { onOpenPalette: () => void }) {
  const { t } = useI18n();
  const { signOut } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const [scrolled, setScrolled] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 4);
    onScroll();
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, []);

  // A drawer that survives navigation would cover the page the user just asked for.
  useEffect(() => {
    setMobileOpen(false);
  }, [location.pathname]);

  return (
    <>
      <a
        href="#main"
        className="sr-only fixed start-4 top-4 z-[100] rounded-lg bg-surface px-4 py-2 text-sm font-semibold shadow-[var(--shadow-lg)] focus:not-sr-only"
      >
        {t('nav.skipToContent')}
      </a>

      <header
        className={cx(
          'sticky top-0 z-40 transition-shadow duration-300',
          'border-b bg-[var(--glass)] backdrop-blur-xl backdrop-saturate-150',
          scrolled ? 'border-hairline shadow-[var(--shadow-sm)]' : 'border-transparent',
        )}
      >
        <div className="mx-auto flex h-16 max-w-[84rem] items-center gap-3 px-4 sm:px-6">
          <Link
            to="/"
            className="flex items-center gap-2.5 rounded-lg transition-transform duration-200 hover:scale-[1.02] active:scale-[0.98]"
          >
            <Logo />
            <Wordmark className="hidden sm:block" />
          </Link>

          <nav aria-label={t('nav.primary')} className="ms-2 hidden items-center gap-0.5 md:flex">
            {NAV.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                end={item.end}
                className={({ isActive }) =>
                  cx(
                    'relative rounded-lg px-3 py-1.5 text-[0.8125rem] font-medium transition-colors duration-200',
                    isActive ? 'text-ink' : 'text-ink-3 hover:text-ink',
                  )
                }
              >
                {({ isActive }) => (
                  <>
                    {isActive && (
                      <span className="absolute inset-0 rounded-lg bg-surface-2 animate-fade-in" aria-hidden="true" />
                    )}
                    <span className="relative">{t(item.labelKey)}</span>
                  </>
                )}
              </NavLink>
            ))}
          </nav>

          <div className="ms-auto flex items-center gap-1">
            <button
              type="button"
              onClick={onOpenPalette}
              className="hidden h-9 items-center gap-2 rounded-lg border border-hairline bg-surface-2 ps-2.5 pe-1.5 text-[0.8125rem] text-ink-3 transition-colors hover:border-hairline-strong hover:text-ink sm:flex"
            >
              <Icon name="search" size={15} />
              <span className="pe-6">{t('header.search')}</span>
              <kbd className="kbd">⌘K</kbd>
            </button>
            <button
              type="button"
              onClick={onOpenPalette}
              aria-label={t('palette.open')}
              className="btn-icon sm:hidden"
            >
              <Icon name="search" size={17} />
            </button>

            <LanguageSwitcher />
            <ThemeToggle />

            <Menu
              label={t('header.account')}
              className="hidden md:block"
              trigger={(props) => (
                <button type="button" {...props} aria-label={t('header.account')} className="btn-icon">
                  <span className="grid h-7 w-7 place-items-center rounded-full bg-[var(--gradient-brand)] text-white">
                    <Icon name="user" size={14} strokeWidth={2} />
                  </span>
                </button>
              )}
            >
              {(close) => (
                <>
                  {/* Navigated imperatively rather than wrapping a <Link>: a link inside the
                      menu item's <button> would be an interactive element inside an interactive
                      element, which no screen reader handles predictably. */}
                  <MenuItem
                    icon={<Icon name="settings" size={15} />}
                    onSelect={() => {
                      close();
                      navigate('/settings');
                    }}
                  >
                    {t('nav.settings')}
                  </MenuItem>
                  <div className="my-1 h-px bg-[var(--border-subtle)]" />
                  <MenuItem
                    icon={<Icon name="logout" size={15} />}
                    onSelect={() => {
                      close();
                      signOut();
                    }}
                  >
                    {t('header.signOut')}
                  </MenuItem>
                </>
              )}
            </Menu>

            <button
              type="button"
              onClick={() => setMobileOpen((open) => !open)}
              aria-label={mobileOpen ? t('nav.closeMenu') : t('nav.openMenu')}
              aria-expanded={mobileOpen}
              className="btn-icon md:hidden"
            >
              <Icon name={mobileOpen ? 'x' : 'menu'} size={18} />
            </button>
          </div>
        </div>

        {mobileOpen && (
          <div className="border-t border-hairline bg-surface md:hidden animate-slide-down">
            <nav aria-label={t('nav.primary')} className="mx-auto max-w-[84rem] px-3 py-2">
              {NAV.map((item, index) => (
                <NavLink
                  key={item.to}
                  to={item.to}
                  end={item.end}
                  style={{ animationDelay: `${index * 30}ms` }}
                  className={({ isActive }) =>
                    cx(
                      'flex items-center gap-3 rounded-lg px-3 py-2.5 text-[0.875rem] font-medium animate-rise-in-sm',
                      isActive ? 'bg-brand-soft text-brand-text' : 'text-ink-2 hover:bg-surface-2',
                    )
                  }
                >
                  <Icon name={item.icon} size={16} />
                  {t(item.labelKey)}
                </NavLink>
              ))}
              <div className="my-2 h-px bg-[var(--border-subtle)]" />
              <button
                type="button"
                onClick={signOut}
                className="flex w-full items-center gap-3 rounded-lg px-3 py-2.5 text-[0.875rem] font-medium text-ink-2 hover:bg-surface-2"
              >
                <Icon name="logout" size={16} />
                {t('header.signOut')}
              </button>
            </nav>
          </div>
        )}
      </header>
    </>
  );
}
