import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import { setTokenProvider } from '../api/client';

const STORAGE_KEY = 'protify.idToken';

// Published at import time rather than from an effect in AuthProvider. React flushes a child's
// passive effects before its parent's, so on a load that already has a token, PortfolioList's
// fetch effect runs first and would go out with the default `() => null` provider — an
// unauthenticated first request, and a 401, on every reload. Reading storage needs no React
// state, so there is nothing to wait for.
setTokenProvider(() => localStorage.getItem(STORAGE_KEY));

interface AuthState {
  token: string | null;
  signIn: (token: string) => void;
  signOut: () => void;
}

const AuthContext = createContext<AuthState | null>(null);

/**
 * Holds the Google ID token the API expects as a bearer (§0.1) and publishes it to the API client
 * through {@link setTokenProvider}, so no component passes a token around and nothing outside
 * this module reads storage.
 *
 * <p>The token is kept in `localStorage`. That is a deliberate trade for a short-lived Google ID
 * token on a demo: it survives a page reload, which the walkthrough needs. A production build
 * would move to a same-site refresh cookie — noted rather than pretended otherwise.
 */
export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [token, setToken] = useState<string | null>(() => localStorage.getItem(STORAGE_KEY));

  const signIn = useCallback((next: string) => {
    localStorage.setItem(STORAGE_KEY, next);
    setToken(next);
  }, []);

  const signOut = useCallback(() => {
    localStorage.removeItem(STORAGE_KEY);
    setToken(null);
  }, []);

  const value = useMemo(() => ({ token, signIn, signOut }), [token, signIn, signOut]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used inside an AuthProvider');
  }
  return context;
}
