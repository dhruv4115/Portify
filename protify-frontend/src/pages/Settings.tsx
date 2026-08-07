import { useToast } from '../components/Toast';
import { useI18n } from '../i18n/I18nProvider';
import { LANGUAGES } from '../i18n/languages';
import { Page, PageHeader } from '../layout/AppShell';
import {
  usePreferences,
  type Density,
  type MotionChoice,
  type ThemeChoice,
} from '../preferences/PreferencesContext';
import { Button } from '../ui/Button';
import { SectionCard } from '../ui/Card';
import { Icon, type IconName } from '../ui/Icon';
import { Delta, StatTile } from '../ui/Stat';
import { cx } from '../ui/cx';

/**
 * Display preferences.
 *
 * <p>Every option here is presented as a card the user can see the consequence of, rather than a
 * dropdown they have to imagine — the theme choices show their own colours, and the density and
 * motion choices sit above a live preview built from real components. A settings page that makes
 * you apply a change to find out what it does is a settings page you visit twice.
 */
export function Settings() {
  const { t } = useI18n();
  const { showToast } = useToast();
  const {
    theme,
    setTheme,
    density,
    setDensity,
    motion,
    setMotion,
    systemReducedMotion,
    reset,
  } = usePreferences();
  const { language, setLanguage } = useI18n();

  const themes: { value: ThemeChoice; label: string; hint?: string; icon: IconName }[] = [
    { value: 'light', label: t('theme.light'), icon: 'sun' },
    { value: 'dark', label: t('theme.dark'), icon: 'moon' },
    { value: 'system', label: t('theme.system'), hint: t('theme.systemHint'), icon: 'monitor' },
  ];

  const densities: { value: Density; label: string }[] = [
    { value: 'comfortable', label: t('settings.density.comfortable') },
    { value: 'compact', label: t('settings.density.compact') },
  ];

  const motions: { value: MotionChoice; label: string; icon: IconName }[] = [
    { value: 'full', label: t('settings.motion.full'), icon: 'sparkles' },
    { value: 'reduced', label: t('settings.motion.reduced'), icon: 'shield' },
  ];

  return (
    <Page className="max-w-[62rem]">
      <PageHeader
        title={t('settings.title')}
        description={t('settings.subtitle')}
        actions={
          <Button
            icon="refresh"
            onClick={() => {
              reset();
              showToast({ variant: 'success', title: t('settings.resetDone') });
            }}
          >
            {t('settings.reset')}
          </Button>
        }
      />

      <div className="space-y-5">
        <SectionCard title={t('settings.appearance')} icon="sun" description={t('settings.appearanceHint')}>
          <div role="radiogroup" aria-label={t('theme.label')} className="grid gap-3 sm:grid-cols-3">
            {themes.map((option) => (
              <button
                key={option.value}
                type="button"
                role="radio"
                aria-checked={theme === option.value}
                onClick={() => setTheme(option.value)}
                className={cx(
                  'group rounded-xl border p-3 text-start transition-all duration-200',
                  theme === option.value
                    ? 'border-brand bg-brand-soft shadow-[0_0_0_3px_var(--brand-soft)]'
                    : 'border-hairline bg-surface-2 hover:border-hairline-strong',
                )}
              >
                <ThemeSwatch variant={option.value} />
                <span className="mt-2.5 flex items-center gap-1.5">
                  <Icon name={option.icon} size={14} className="text-ink-3" />
                  <span className="text-[0.8125rem] font-semibold text-ink">{option.label}</span>
                  {theme === option.value && (
                    <Icon name="check" size={14} className="ms-auto text-brand" strokeWidth={2.4} />
                  )}
                </span>
                {option.hint && <span className="mt-0.5 block text-[0.75rem] text-ink-3">{option.hint}</span>}
              </button>
            ))}
          </div>
        </SectionCard>

        <SectionCard title={t('settings.language')} icon="globe" description={t('language.hint')}>
          <div
            role="radiogroup"
            aria-label={t('language.label')}
            className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3"
          >
            {LANGUAGES.map((entry) => (
              <button
                key={entry.code}
                type="button"
                role="radio"
                aria-checked={language === entry.code}
                onClick={() => setLanguage(entry.code)}
                className={cx(
                  'flex items-center gap-3 rounded-xl border px-3 py-2.5 text-start transition-all duration-200',
                  language === entry.code
                    ? 'border-brand bg-brand-soft'
                    : 'border-hairline bg-surface-2 hover:border-hairline-strong',
                )}
              >
                <span className="grid h-8 w-8 shrink-0 place-items-center rounded-lg border border-hairline bg-surface text-[0.6875rem] font-bold uppercase text-ink-2">
                  {entry.code}
                </span>
                <span className="min-w-0 flex-1">
                  {/* `lang` but no `dir`. The glyphs still shape and order right-to-left on their
                      own, while the line keeps the list's alignment — a single right-aligned row
                      in an otherwise left-aligned picker reads as a layout bug, not as respect. */}
                  <span className="block truncate text-[0.875rem] font-medium text-ink" lang={entry.code}>
                    {entry.endonym}
                  </span>
                  <span className="block truncate text-[0.75rem] text-ink-3">{entry.english}</span>
                </span>
                {language === entry.code && <Icon name="check" size={15} className="text-brand" strokeWidth={2.4} />}
              </button>
            ))}
          </div>
          <p className="mt-4 flex items-start gap-1.5 border-t border-hairline-subtle pt-3 text-[0.75rem] text-ink-3">
            <Icon name="info" size={13} className="mt-0.5" />
            {t('language.sourceNote')}
          </p>
        </SectionCard>

        <div className="grid gap-5 lg:grid-cols-2">
          <SectionCard title={t('settings.density')} icon="list" description={t('settings.densityHint')}>
            <div role="radiogroup" aria-label={t('settings.density')} className="grid gap-2 sm:grid-cols-2">
              {densities.map((option) => (
                <OptionRow
                  key={option.value}
                  selected={density === option.value}
                  onSelect={() => setDensity(option.value)}
                  label={option.label}
                />
              ))}
            </div>
          </SectionCard>

          <SectionCard title={t('settings.motion')} icon="sparkles" description={t('settings.motionHint')}>
            <div role="radiogroup" aria-label={t('settings.motion')} className="grid gap-2 sm:grid-cols-2">
              {motions.map((option) => (
                <OptionRow
                  key={option.value}
                  selected={motion === option.value}
                  onSelect={() => setMotion(option.value)}
                  label={option.label}
                  icon={option.icon}
                />
              ))}
            </div>
            {systemReducedMotion && (
              <p className="mt-3 flex items-start gap-1.5 text-[0.75rem] text-ink-3">
                <Icon name="info" size={13} className="mt-0.5" />
                {t('settings.motionSystemNote')}
              </p>
            )}
          </SectionCard>
        </div>

        <SectionCard title={t('settings.preview')} icon="activity" description={t('settings.previewHint')}>
          <div className="grid gap-4 sm:grid-cols-3">
            <div className="rounded-xl border border-hairline bg-surface-2 p-4">
              <StatTile
                label={t('portfolio.totalValue')}
                value="$128,430.55"
                sub={<Delta money={{ amount: '4210.20', currency: 'USD' }} percent="3.4000" size="sm" />}
              />
            </div>
            <div className="rounded-xl border border-hairline bg-surface-2 p-4">
              <StatTile
                label={t('portfolio.unrealisedPnl')}
                value="-$2,104.10"
                sub={<Delta money={{ amount: '-2104.10', currency: 'USD' }} percent="-1.6000" size="sm" />}
              />
            </div>
            <div className="rounded-xl border border-hairline bg-surface-2 p-4">
              <StatTile label={t('portfolio.cash')} value="$8,000.00" sub={<span className="text-ink-3">USD</span>} />
            </div>
          </div>
        </SectionCard>

        <p className="flex items-start gap-1.5 px-1 text-[0.75rem] text-ink-3">
          <Icon name="shield" size={13} className="mt-0.5" />
          {t('settings.storageNote')}
        </p>
      </div>
    </Page>
  );
}

function OptionRow({
  selected,
  onSelect,
  label,
  icon,
}: {
  selected: boolean;
  onSelect: () => void;
  label: string;
  icon?: IconName;
}) {
  return (
    <button
      type="button"
      role="radio"
      aria-checked={selected}
      onClick={onSelect}
      className={cx(
        'flex items-center gap-2.5 rounded-xl border px-3 py-2.5 text-start transition-all duration-200',
        selected ? 'border-brand bg-brand-soft' : 'border-hairline bg-surface-2 hover:border-hairline-strong',
      )}
    >
      {icon && <Icon name={icon} size={15} className={selected ? 'text-brand' : 'text-ink-3'} />}
      <span className="flex-1 text-[0.875rem] font-medium text-ink">{label}</span>
      {selected && <Icon name="check" size={15} className="text-brand" strokeWidth={2.4} />}
    </button>
  );
}

/**
 * A miniature of the interface in each theme, drawn with fixed colours rather than tokens — the
 * light swatch has to look light even while the app is dark, which is exactly what a token would
 * prevent.
 */
function ThemeSwatch({ variant }: { variant: ThemeChoice }) {
  if (variant === 'system') {
    return (
      <span className="flex h-14 overflow-hidden rounded-lg border border-hairline">
        <span className="w-1/2">
          <SwatchBody plane="#f6f7f9" surface="#ffffff" line="#d3d8e0" accent="#4f46e5" />
        </span>
        <span className="w-1/2">
          <SwatchBody plane="#0a0b0f" surface="#12141a" line="#333a4a" accent="#818cf8" />
        </span>
      </span>
    );
  }

  const dark = variant === 'dark';
  return (
    <span className="block h-14 overflow-hidden rounded-lg border border-hairline">
      <SwatchBody
        plane={dark ? '#0a0b0f' : '#f6f7f9'}
        surface={dark ? '#12141a' : '#ffffff'}
        line={dark ? '#333a4a' : '#d3d8e0'}
        accent={dark ? '#818cf8' : '#4f46e5'}
      />
    </span>
  );
}

function SwatchBody({
  plane,
  surface,
  line,
  accent,
}: {
  plane: string;
  surface: string;
  line: string;
  accent: string;
}) {
  return (
    <span className="flex h-full w-full flex-col gap-1 p-1.5" style={{ background: plane }}>
      <span className="flex items-center gap-1 rounded-sm px-1 py-1" style={{ background: surface }}>
        <span className="h-1.5 w-1.5 rounded-full" style={{ background: accent }} />
        <span className="h-1 w-5 rounded-full" style={{ background: line }} />
      </span>
      <span className="flex-1 rounded-sm p-1" style={{ background: surface }}>
        <span className="block h-1 w-8 rounded-full" style={{ background: line }} />
        <span className="mt-1 block h-1 w-6 rounded-full" style={{ background: accent, opacity: 0.7 }} />
      </span>
    </span>
  );
}
