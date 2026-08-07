import { useEffect, useRef, useState } from 'react';
import { useI18n } from '../i18n/I18nProvider';
import { LanguageSwitcher } from '../layout/LanguageSwitcher';
import { Logo, Wordmark } from '../layout/Logo';
import { ThemeToggle } from '../layout/ThemeToggle';
import { Button } from '../ui/Button';
import { Field, TextInput } from '../ui/Field';
import { Icon, type IconName } from '../ui/Icon';
import { useAuth } from './AuthContext';

const CLIENT_ID = import.meta.env.VITE_GOOGLE_CLIENT_ID as string | undefined;
const GSI_SRC = 'https://accounts.google.com/gsi/client';

declare global {
  interface Window {
    google?: {
      accounts: {
        id: {
          initialize: (config: { client_id: string; callback: (r: { credential: string }) => void }) => void;
          renderButton: (parent: HTMLElement, options: Record<string, string>) => void;
        };
      };
    };
  }
}

const FEATURES: {
  icon: IconName;
  title: 'signIn.feature.multiCurrency' | 'signIn.feature.honest' | 'signIn.feature.analytics';
  body: 'signIn.feature.multiCurrencyBody' | 'signIn.feature.honestBody' | 'signIn.feature.analyticsBody';
}[] = [
  { icon: 'globe', title: 'signIn.feature.multiCurrency', body: 'signIn.feature.multiCurrencyBody' },
  { icon: 'shield', title: 'signIn.feature.honest', body: 'signIn.feature.honestBody' },
  { icon: 'activity', title: 'signIn.feature.analytics', body: 'signIn.feature.analyticsBody' },
];

/**
 * Google Identity Services renders the button and hands back an ID token, which is exactly the
 * bearer the resource server validates — no token is minted, parsed or stored by us beyond
 * passing it along.
 *
 * <p>Without `VITE_GOOGLE_CLIENT_ID` the screen falls back to pasting a token. That keeps the API
 * demonstrable on a machine with no OAuth client configured, which is the same "the demo must not
 * depend on a live third party" rule the backend's fallback chain follows.
 *
 * <p>The language and theme controls are on this screen too, above the fold. Somebody who cannot
 * read English should not have to sign in first to find the control that would let them.
 */
export function SignIn() {
  const { signIn } = useAuth();
  const { t } = useI18n();
  const buttonRef = useRef<HTMLDivElement>(null);
  const [manualToken, setManualToken] = useState('');

  useEffect(() => {
    if (!CLIENT_ID || !buttonRef.current) {
      return;
    }
    const script = document.createElement('script');
    script.src = GSI_SRC;
    script.async = true;
    script.onload = () => {
      if (!window.google || !buttonRef.current) {
        return;
      }
      window.google.accounts.id.initialize({
        client_id: CLIENT_ID,
        callback: (response) => signIn(response.credential),
      });
      window.google.accounts.id.renderButton(buttonRef.current, { theme: 'outline', size: 'large' });
    };
    document.body.appendChild(script);
    return () => script.remove();
  }, [signIn]);

  return (
    <div className="flex min-h-dvh flex-col lg:flex-row">
      {/* Left: the pitch. Hidden on small screens, where it would push the actual sign-in
          control below the fold — the one thing this page exists to offer. */}
      <aside className="relative hidden overflow-hidden lg:flex lg:w-[46%] lg:flex-col lg:justify-between lg:p-12">
        <div className="absolute inset-0" style={{ background: 'var(--gradient-brand)' }} aria-hidden="true" />
        <div
          className="absolute inset-0 opacity-30"
          style={{
            background:
              'radial-gradient(70% 60% at 20% 10%, rgb(255 255 255 / 0.35) 0%, transparent 60%), radial-gradient(50% 50% at 90% 90%, rgb(0 0 0 / 0.35) 0%, transparent 60%)',
          }}
          aria-hidden="true"
        />

        <div className="relative flex items-center gap-2.5">
          <span className="grid h-8 w-8 place-items-center rounded-lg bg-white/15 backdrop-blur">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path
                d="M7 19V5h6.4a4.6 4.6 0 0 1 0 9.2H10"
                stroke="white"
                strokeWidth="2.6"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
          </span>
          <span className="text-[0.9375rem] font-bold tracking-[-0.02em] text-white">Protify</span>
        </div>

        <div className="relative">
          <h2 className="max-w-md text-[2rem] font-bold leading-[1.15] tracking-[-0.03em] text-white">
            {t('app.tagline')}
          </h2>
          <ul className="mt-9 space-y-6">
            {FEATURES.map((feature, index) => (
              <li key={feature.title} className={`flex gap-3.5 animate-rise-in stagger-${index + 1}`}>
                <span className="mt-0.5 grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-white/15 text-white backdrop-blur">
                  <Icon name={feature.icon} size={17} />
                </span>
                <span>
                  <span className="block text-[0.9375rem] font-semibold text-white">{t(feature.title)}</span>
                  <span className="mt-0.5 block max-w-sm text-[0.8125rem] leading-relaxed text-white/75">
                    {t(feature.body)}
                  </span>
                </span>
              </li>
            ))}
          </ul>
        </div>

        <p className="relative text-[0.75rem] text-white/60">
          {t('footer.rights', { year: new Date().getFullYear() })}
        </p>
      </aside>

      {/* Right: the form. */}
      <main className="aurora relative flex flex-1 flex-col">
        <div className="relative z-1 flex justify-end gap-1 p-4">
          <LanguageSwitcher />
          <ThemeToggle />
        </div>

        <div className="relative z-1 flex flex-1 items-center justify-center px-5 pb-16">
          <div className="w-full max-w-sm animate-rise-in">
            <div className="flex items-center gap-2.5 lg:hidden">
              <Logo />
              <Wordmark />
            </div>

            <h1 className="mt-6 text-[1.5rem] font-bold tracking-[-0.03em] text-ink lg:mt-0">
              {t('signIn.heading')}
            </h1>
            <p className="mt-2 text-[0.875rem] text-ink-3">{t('signIn.subheading')}</p>

            <div className="card card-sheen mt-7">
              {CLIENT_ID ? (
                <div ref={buttonRef} className="flex justify-center [color-scheme:light]" />
              ) : (
                <form
                  onSubmit={(event) => {
                    event.preventDefault();
                    if (manualToken.trim()) {
                      signIn(manualToken.trim());
                    }
                  }}
                  className="space-y-4"
                >
                  <Field
                    label={t('signIn.tokenLabel')}
                    htmlFor="manual-token"
                    hint={
                      <>
                        Set <code className="kbd">VITE_GOOGLE_CLIENT_ID</code> in{' '}
                        <code className="kbd">.env.local</code> for the real sign-in button.
                      </>
                    }
                  >
                    <TextInput
                      id="manual-token"
                      value={manualToken}
                      onChange={(event) => setManualToken(event.target.value)}
                      placeholder="eyJhbGciOi…"
                      autoComplete="off"
                      spellCheck={false}
                      className="font-mono text-[0.8125rem]"
                    />
                  </Field>
                  <Button type="submit" variant="primary" size="lg" className="w-full">
                    {t('signIn.continue')}
                  </Button>
                </form>
              )}
            </div>

            <p className="mt-5 flex items-start gap-1.5 text-[0.75rem] leading-relaxed text-ink-3">
              <Icon name="shield" size={13} className="mt-0.5 shrink-0" />
              {t('language.hint')}
            </p>
          </div>
        </div>
      </main>
    </div>
  );
}
