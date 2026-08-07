import { BrowserRouter, Route, Routes } from 'react-router-dom';
import { AuthProvider, useAuth } from './auth/AuthContext';
import { ToastProvider } from './components/Toast';
import { SignIn } from './auth/SignIn';
import { I18nProvider } from './i18n/I18nProvider';
import { AppShell, ScrollToTop } from './layout/AppShell';
import { PreferencesProvider } from './preferences/PreferencesContext';
import { About } from './pages/About';
import { NotFound } from './pages/NotFound';
import { Overview } from './pages/Overview';
import { PortfolioDetail } from './pages/PortfolioDetail';
import { PortfolioList } from './pages/PortfolioList';
import { Settings } from './pages/Settings';

/**
 * Provider order is deliberate, outermost first:
 *
 * <ol>
 *   <li>{@link PreferencesProvider} — writes theme and density onto `<html>`; everything below
 *       renders into whatever it has already established.
 *   <li>{@link I18nProvider} — publishes `t`, which the auth screen needs before anyone is signed
 *       in, so it has to sit above the auth boundary rather than inside it.
 *   <li>{@link AuthProvider} — decides whether the app or the sign-in screen renders at all.
 *   <li>{@link ToastProvider} — notifications must outlive any single route.
 *   <li>Router — last, so every provider above is available to every route.
 * </ol>
 */
export function App() {
  return (
    <PreferencesProvider>
      <I18nProvider>
        <AuthProvider>
          <ToastProvider>
            <BrowserRouter>
              <Authenticated />
            </BrowserRouter>
          </ToastProvider>
        </AuthProvider>
      </I18nProvider>
    </PreferencesProvider>
  );
}

/**
 * The routes.
 *
 * <p>`/portfolios/:id` stays a single scrolling page holding every panel it has always held —
 * chart, analytics, allocation, insights, holdings, the add form and the transaction list. The
 * extra routes around it are for things that genuinely are separate destinations (a cross-
 * portfolio overview, preferences, product information); none of them takes anything *away* from
 * the detail page, because splitting one workflow across tabs would buy tidiness with clicks.
 */
function Authenticated() {
  const { token } = useAuth();

  if (!token) {
    return <SignIn />;
  }

  return (
    <AppShell>
      <ScrollToTop />
      <Routes>
        <Route path="/" element={<Overview />} />
        <Route path="/portfolios" element={<PortfolioList />} />
        <Route path="/portfolios/:id" element={<PortfolioDetail />} />
        <Route path="/settings" element={<Settings />} />
        <Route path="/about" element={<About />} />
        <Route path="*" element={<NotFound />} />
      </Routes>
    </AppShell>
  );
}
