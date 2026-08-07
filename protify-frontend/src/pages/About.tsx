import { useI18n } from '../i18n/I18nProvider';
import { Page, PageHeader } from '../layout/AppShell';
import { Card, SectionCard } from '../ui/Card';
import { Icon, type IconName } from '../ui/Icon';

const PRINCIPLES: { icon: IconName; title: 'about.principle.exact' | 'about.principle.honest' | 'about.principle.currency' | 'about.principle.accessible'; body: 'about.principle.exactBody' | 'about.principle.honestBody' | 'about.principle.currencyBody' | 'about.principle.accessibleBody' }[] = [
  { icon: 'shield', title: 'about.principle.exact', body: 'about.principle.exactBody' },
  { icon: 'alert', title: 'about.principle.honest', body: 'about.principle.honestBody' },
  { icon: 'globe', title: 'about.principle.currency', body: 'about.principle.currencyBody' },
  { icon: 'user', title: 'about.principle.accessible', body: 'about.principle.accessibleBody' },
];

const STACK = ['React 19', 'TypeScript', 'Vite', 'Tailwind CSS', 'Recharts', 'Spring Boot', 'PostgreSQL', 'Docker'];

const SHORTCUTS: { keys: string[]; label: 'about.shortcut.palette' | 'about.shortcut.theme' | 'about.shortcut.search' | 'about.shortcut.close' }[] = [
  { keys: ['⌘', 'K'], label: 'about.shortcut.palette' },
  { keys: ['⌘', 'K', '→', 'theme'], label: 'about.shortcut.theme' },
  { keys: ['⌘', 'K'], label: 'about.shortcut.search' },
  { keys: ['esc'], label: 'about.shortcut.close' },
];

export function About() {
  const { t } = useI18n();

  return (
    <Page className="max-w-[62rem]">
      <PageHeader title={t('about.title')} description={t('about.lede')} />

      <div className="space-y-5">
        <SectionCard title={t('about.principles')} icon="sparkles">
          <div className="grid gap-4 sm:grid-cols-2">
            {PRINCIPLES.map((principle, index) => (
              <div
                key={principle.title}
                className={`rounded-xl border border-hairline bg-surface-2 p-4 animate-rise-in stagger-${index}`}
              >
                <span className="grid h-9 w-9 place-items-center rounded-lg bg-brand-soft text-brand-text">
                  <Icon name={principle.icon} size={17} />
                </span>
                <h3 className="mt-3 text-[0.9375rem] font-semibold tracking-[-0.01em] text-ink">
                  {t(principle.title)}
                </h3>
                <p className="mt-1.5 text-[0.8125rem] leading-relaxed text-ink-3">{t(principle.body)}</p>
              </div>
            ))}
          </div>
        </SectionCard>

        <div className="grid gap-5 lg:grid-cols-2">
          <SectionCard id="shortcuts" title={t('about.shortcuts')} icon="command">
            <ul className="space-y-2.5">
              {SHORTCUTS.map((shortcut, index) => (
                <li key={index} className="flex items-center justify-between gap-4">
                  <span className="text-[0.8125rem] text-ink-2">{t(shortcut.label)}</span>
                  <span className="flex shrink-0 items-center gap-1">
                    {shortcut.keys.map((key, keyIndex) => (
                      <kbd key={keyIndex} className="kbd">
                        {key}
                      </kbd>
                    ))}
                  </span>
                </li>
              ))}
            </ul>
          </SectionCard>

          <SectionCard title={t('about.stack')} icon="layers">
            <ul className="flex flex-wrap gap-2">
              {STACK.map((item) => (
                <li key={item} className="badge">
                  {item}
                </li>
              ))}
            </ul>
          </SectionCard>
        </div>

        <Card className="overflow-hidden">
          <div
            className="absolute inset-0 opacity-[0.06]"
            style={{ background: 'var(--gradient-brand)' }}
            aria-hidden="true"
          />
          <div className="relative">
            <p className="card-eyebrow">{t('app.name')}</p>
            <p className="mt-2 max-w-2xl text-[1.0625rem] leading-relaxed tracking-[-0.01em] text-ink">
              {t('app.description')}
            </p>
          </div>
        </Card>
      </div>
    </Page>
  );
}
