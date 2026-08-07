import { useEffect, useState, type ReactNode } from 'react';
import { useLocation } from 'react-router-dom';
import { CommandPalette } from './CommandPalette';
import { Footer } from './Footer';
import { Header } from './Header';

/**
 * The frame every signed-in page renders inside: header, content, footer, and the two things that
 * are global by nature — the command palette and its keyboard shortcut.
 *
 * <p>The shortcut is registered once here rather than inside the palette, so ⌘K works from
 * anywhere including while another dialog has focus.
 */
export function AppShell({ children }: { children: ReactNode }) {
  const [paletteOpen, setPaletteOpen] = useState(false);
  const location = useLocation();

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      // metaKey for macOS, ctrlKey everywhere else — checking both means one binding, no sniffing.
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setPaletteOpen((open) => !open);
      }
    }
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);

  return (
    <div className="flex min-h-dvh flex-col">
      <Header onOpenPalette={() => setPaletteOpen(true)} />

      <main id="main" className="aurora flex-1">
        {/* Keying on the path remounts the subtree per navigation, which is what re-runs the
            entrance animation. Without it React reuses the DOM and the transition never plays. */}
        <div key={location.pathname} className="relative z-1 animate-rise-in">
          {children}
        </div>
      </main>

      <Footer />

      <CommandPalette open={paletteOpen} onClose={() => setPaletteOpen(false)} />
    </div>
  );
}

/**
 * The standard page frame — one column, capped width, consistent gutters.
 *
 * <p>84rem rather than full-bleed: financial tables are read left to right across their columns,
 * and a row that spans a 32-inch monitor forces the eye to travel further than it can track.
 */
export function Page({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <div className={`mx-auto w-full max-w-[84rem] px-4 py-6 sm:px-6 sm:py-8 ${className ?? ''}`}>
      {children}
    </div>
  );
}

interface PageHeaderProps {
  title: ReactNode;
  eyebrow?: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  children?: ReactNode;
}

export function PageHeader({ title, eyebrow, description, actions, children }: PageHeaderProps) {
  return (
    <header className="mb-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="min-w-0">
          {eyebrow}
          <h1 className="text-[1.5rem] font-bold tracking-[-0.025em] text-ink sm:text-[1.75rem]">{title}</h1>
          {description && <p className="mt-1.5 max-w-2xl text-[0.875rem] text-ink-3">{description}</p>}
        </div>
        {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
      </div>
      {children}
    </header>
  );
}

/** Scrolls to the top on navigation — otherwise a deep link lands mid-page from the last route. */
export function ScrollToTop() {
  const { pathname, hash } = useLocation();

  useEffect(() => {
    if (hash) {
      document.getElementById(hash.slice(1))?.scrollIntoView({ behavior: 'smooth', block: 'start' });
      return;
    }
    // Assigning `scrollTop` rather than calling `window.scrollTo`. The two are equivalent for an
    // instant jump, but jsdom has no scroll implementation and logs a "Not implemented" error to
    // stderr for the function call — noise in every test run, for no behavioural difference.
    document.documentElement.scrollTop = 0;
    document.body.scrollTop = 0;
  }, [pathname, hash]);

  return null;
}
