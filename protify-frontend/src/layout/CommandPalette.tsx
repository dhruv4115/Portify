import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { getPortfolios } from '../api/endpoints';
import type { Portfolio } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { useI18n } from '../i18n/I18nProvider';
import { LANGUAGES } from '../i18n/languages';
import { formatMoney } from '../lib/formatMoney';
import { usePreferences } from '../preferences/PreferencesContext';
import { Icon, type IconName } from '../ui/Icon';
import { cx } from '../ui/cx';

interface Command {
  id: string;
  group: string;
  label: string;
  hint?: string;
  icon: IconName;
  /** Extra words that should match this command without being shown. */
  keywords?: string;
  run: () => void;
}

/**
 * ⌘K — jump to any portfolio, page or action without leaving the keyboard.
 *
 * <p>Deliberately a *superset* of the navigation rather than a replacement for it. Everything
 * reachable here is also reachable by clicking; the palette is the shortcut for people who know
 * where they are going, which is the only kind of power-user feature worth building.
 */
export function CommandPalette({ open, onClose }: { open: boolean; onClose: () => void }) {
  const navigate = useNavigate();
  const { t, setLanguage, language } = useI18n();
  const { toggleTheme } = usePreferences();
  const { signOut } = useAuth();

  const [query, setQuery] = useState('');
  const [active, setActive] = useState(0);
  const [portfolios, setPortfolios] = useState<Portfolio[]>([]);
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);

  // Fetched per opening rather than cached for the session: a portfolio created two minutes ago
  // should be here, and one list request is cheaper than a stale result is confusing.
  useEffect(() => {
    if (!open) {
      return;
    }
    let cancelled = false;
    getPortfolios()
      .then((loaded) => !cancelled && setPortfolios(loaded))
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [open]);

  useEffect(() => {
    if (open) {
      setQuery('');
      setActive(0);
      // Deferred a frame: the input does not exist until this render commits.
      requestAnimationFrame(() => inputRef.current?.focus());
    }
  }, [open]);

  useEffect(() => {
    if (!open) {
      return;
    }
    const { overflow } = document.body.style;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = overflow;
    };
  }, [open]);

  const commands = useMemo<Command[]>(() => {
    const go = (path: string) => () => {
      navigate(path);
      onClose();
    };

    const portfolioCommands: Command[] = portfolios.map((portfolio) => ({
      id: `portfolio-${portfolio.id}`,
      group: t('palette.group.portfolios'),
      label: portfolio.name,
      hint: `${formatMoney(portfolio.totalValue)} · ${portfolio.baseCurrency}`,
      icon: 'wallet',
      keywords: portfolio.baseCurrency,
      run: go(`/portfolios/${portfolio.id}`),
    }));

    const navigation: Command[] = [
      { id: 'nav-overview', label: t('nav.overview'), icon: 'activity', run: go('/') },
      { id: 'nav-portfolios', label: t('nav.portfolios'), icon: 'layers', run: go('/portfolios') },
      { id: 'nav-settings', label: t('nav.settings'), icon: 'settings', run: go('/settings') },
      { id: 'nav-about', label: t('nav.about'), icon: 'info', run: go('/about') },
    ].map((entry) => ({ ...entry, group: t('palette.group.navigation') }) as Command);

    const actions: Command[] = [
      {
        id: 'action-new',
        group: t('palette.group.actions'),
        label: t('palette.action.newPortfolio'),
        icon: 'plus',
        run: go('/portfolios?new=1'),
      },
      {
        id: 'action-theme',
        group: t('palette.group.actions'),
        label: t('palette.action.toggleTheme'),
        icon: 'moon',
        keywords: 'dark light appearance',
        run: () => {
          toggleTheme();
          onClose();
        },
      },
      {
        id: 'action-signout',
        group: t('palette.group.actions'),
        label: t('palette.action.signOut'),
        icon: 'logout',
        run: () => {
          signOut();
          onClose();
        },
      },
    ];

    const languages: Command[] = LANGUAGES.filter((entry) => entry.code !== language).map((entry) => ({
      id: `language-${entry.code}`,
      group: t('palette.group.language'),
      label: entry.endonym,
      hint: entry.english,
      icon: 'globe',
      keywords: `${entry.english} ${entry.code} language translate`,
      run: () => {
        setLanguage(entry.code);
        onClose();
      },
    }));

    return [...portfolioCommands, ...navigation, ...actions, ...languages];
  }, [portfolios, navigate, onClose, t, toggleTheme, signOut, setLanguage, language]);

  const results = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) {
      // Unfiltered, the palette leads with portfolios — the thing people open it to reach.
      return commands.filter((command) => command.group !== t('palette.group.language'));
    }
    return commands.filter((command) =>
      `${command.label} ${command.hint ?? ''} ${command.keywords ?? ''} ${command.group}`
        .toLowerCase()
        .includes(needle),
    );
  }, [commands, query, t]);

  useEffect(() => {
    setActive(0);
  }, [query]);

  // Keeps the highlighted row in view when arrowing past the fold.
  useEffect(() => {
    listRef.current?.querySelector('[data-active="true"]')?.scrollIntoView({ block: 'nearest' });
  }, [active]);

  if (!open) {
    return null;
  }

  const grouped = results.reduce<Record<string, Command[]>>((accumulator, command) => {
    (accumulator[command.group] ??= []).push(command);
    return accumulator;
  }, {});

  function onKeyDown(event: React.KeyboardEvent) {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setActive((current) => (current + 1) % Math.max(results.length, 1));
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      setActive((current) => (current - 1 + results.length) % Math.max(results.length, 1));
    } else if (event.key === 'Enter') {
      event.preventDefault();
      results[active]?.run();
    } else if (event.key === 'Escape') {
      event.preventDefault();
      onClose();
    }
  }

  let flatIndex = -1;

  return (
    <div
      className="fixed inset-0 z-[95] flex items-start justify-center bg-[var(--overlay)] px-4 pt-[12vh] backdrop-blur-[3px] animate-fade-in"
      role="presentation"
      onMouseDown={onClose}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label={t('palette.title')}
        className="card-glass w-full max-w-xl overflow-hidden rounded-2xl border shadow-[var(--shadow-xl)] animate-pop-in"
        onMouseDown={(event) => event.stopPropagation()}
        onKeyDown={onKeyDown}
      >
        <div className="flex items-center gap-2.5 border-b border-hairline px-4">
          <Icon name="search" size={17} className="text-ink-3" />
          <input
            ref={inputRef}
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder={t('palette.placeholder')}
            aria-label={t('palette.title')}
            aria-controls="command-results"
            aria-activedescendant={results[active] ? `command-${results[active].id}` : undefined}
            role="combobox"
            aria-expanded="true"
            autoComplete="off"
            className="h-14 flex-1 bg-transparent text-[0.9375rem] outline-none placeholder:text-ink-3"
          />
          <kbd className="kbd">esc</kbd>
        </div>

        <div ref={listRef} id="command-results" role="listbox" className="max-h-[22rem] overflow-y-auto p-2">
          {results.length === 0 ? (
            <div className="px-3 py-10 text-center">
              <p className="text-[0.875rem] font-semibold text-ink">{t('palette.empty')}</p>
              <p className="mt-1 text-[0.8125rem] text-ink-3">{t('palette.emptyHint')}</p>
            </div>
          ) : (
            Object.entries(grouped).map(([group, items]) => (
              <div key={group} className="mb-1 last:mb-0">
                <p className="px-2.5 py-1.5 text-[0.6875rem] font-semibold uppercase tracking-[0.07em] text-ink-3">
                  {group}
                </p>
                {items.map((command) => {
                  flatIndex += 1;
                  const index = flatIndex;
                  const isActive = index === active;
                  return (
                    <button
                      key={command.id}
                      id={`command-${command.id}`}
                      type="button"
                      role="option"
                      aria-selected={isActive}
                      data-active={isActive}
                      onMouseMove={() => setActive(index)}
                      onClick={command.run}
                      className={cx(
                        'flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-start transition-colors',
                        isActive ? 'bg-brand-soft text-ink' : 'text-ink-2 hover:bg-surface-2',
                      )}
                    >
                      <Icon name={command.icon} size={15} className={isActive ? 'text-brand' : 'text-ink-3'} />
                      <span className="flex-1 truncate text-[0.875rem]">{command.label}</span>
                      {command.hint && (
                        <span className="shrink-0 text-[0.75rem] tabular-nums text-ink-3">{command.hint}</span>
                      )}
                      {isActive && <Icon name="enter" size={13} className="text-ink-3" />}
                    </button>
                  );
                })}
              </div>
            ))
          )}
        </div>

        <div className="flex flex-wrap items-center gap-x-4 gap-y-1 border-t border-hairline px-4 py-2.5 text-[0.6875rem] text-ink-3">
          <span className="flex items-center gap-1">
            <kbd className="kbd">↑</kbd>
            <kbd className="kbd">↓</kbd>
            {t('palette.hintNavigate')}
          </span>
          <span className="flex items-center gap-1">
            <kbd className="kbd">↵</kbd>
            {t('palette.hintSelect')}
          </span>
          <span className="flex items-center gap-1">
            <kbd className="kbd">esc</kbd>
            {t('palette.hintClose')}
          </span>
        </div>
      </div>
    </div>
  );
}
