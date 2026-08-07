import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from 'react';
import { ApiError } from '../api/client';

type ToastVariant = 'success' | 'error';

export interface ToastMessage {
  id: number;
  variant: ToastVariant;
  title: string;
  detail?: string;
}

export interface ToastInput {
  variant?: ToastVariant;
  title: string;
  detail?: string;
}

interface ToastContextValue {
  showToast: (toast: ToastInput) => void;
}

const ToastContext = createContext<ToastContextValue | null>(null);

const AUTO_DISMISS_MS = 6000;

/**
 * App-wide notifications for failures (and confirmations) that are not about one form field —
 * a currency switch or a delete either affects the whole screen or fails for a reason no single
 * input owns. Field-level mistakes stay exactly where they are today, next to the input
 * (`fieldErrors()` in `api/client.ts`); this is for everything else.
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastMessage[]>([]);
  const nextId = useRef(0);

  const dismiss = useCallback((id: number) => {
    setToasts((current) => current.filter((toast) => toast.id !== id));
  }, []);

  const showToast = useCallback(
    ({ variant = 'error', title, detail }: ToastInput) => {
      const id = ++nextId.current;
      setToasts((current) => [...current, { id, variant, title, detail }]);
      setTimeout(() => dismiss(id), AUTO_DISMISS_MS);
    },
    [dismiss],
  );

  return (
    <ToastContext.Provider value={{ showToast }}>
      {children}
      <div className="toast-viewport no-print" role="status" aria-live="polite">
        {toasts.map((toast) => (
          <div key={toast.id} className={`toast toast-${toast.variant}`} data-testid="toast">
            <span
              className={`mt-px grid h-[1.125rem] w-[1.125rem] shrink-0 place-items-center rounded-full ${
                toast.variant === 'success' ? 'bg-pos-soft text-pos' : 'bg-neg-soft text-neg'
              }`}
              aria-hidden="true"
            >
              <svg viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
                {toast.variant === 'success' ? <path d="m4 12 5 5L20 6" /> : <path d="M18 6 6 18M6 6l12 12" />}
              </svg>
            </span>
            <div className="min-w-0">
              <div className="toast-title">{toast.title}</div>
              {toast.detail && <div className="toast-detail">{toast.detail}</div>}
            </div>
            <button
              type="button"
              className="toast-close"
              aria-label="Dismiss notification"
              onClick={() => dismiss(toast.id)}
            >
              <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" aria-hidden="true">
                <path d="M18 6 6 18M6 6l12 12" />
              </svg>
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast(): ToastContextValue {
  const context = useContext(ToastContext);
  if (!context) {
    throw new Error('useToast must be used within a ToastProvider');
  }
  return context;
}

/** Turns whatever a failed request threw into the input {@link useToast}'s `showToast` wants. */
export function toastFromError(cause: unknown, fallbackTitle: string): ToastInput {
  if (cause instanceof ApiError) {
    return { variant: 'error', title: cause.problem.title ?? fallbackTitle, detail: cause.problem.detail };
  }
  return { variant: 'error', title: fallbackTitle };
}
