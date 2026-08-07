import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import { ToastProvider, toastFromError, useToast } from '../components/Toast';

function Trigger({ title = 'Something happened', variant = 'error' as const, detail }: { title?: string; variant?: 'error' | 'success'; detail?: string }) {
  const { showToast } = useToast();
  return (
    <button type="button" onClick={() => showToast({ title, variant, detail })}>
      Fire
    </button>
  );
}

describe('ToastProvider / useToast', () => {
  it('renders nothing until a toast is shown', () => {
    render(
      <ToastProvider>
        <Trigger />
      </ToastProvider>,
    );

    expect(screen.queryByTestId('toast')).not.toBeInTheDocument();
  });

  it('shows a toast with its title and detail after showToast is called', async () => {
    const user = userEvent.setup();
    render(
      <ToastProvider>
        <Trigger title="Could not switch base currency." detail="The FX rate table has no INR/USD rate." />
      </ToastProvider>,
    );

    await user.click(screen.getByRole('button', { name: 'Fire' }));

    const toast = await screen.findByTestId('toast');
    expect(toast).toHaveTextContent('Could not switch base currency.');
    expect(toast).toHaveTextContent('The FX rate table has no INR/USD rate.');
    expect(toast).toHaveClass('toast-error');
  });

  it('dismisses a toast when its close button is clicked', async () => {
    const user = userEvent.setup();
    render(
      <ToastProvider>
        <Trigger />
      </ToastProvider>,
    );

    await user.click(screen.getByRole('button', { name: 'Fire' }));
    await screen.findByTestId('toast');

    await user.click(screen.getByRole('button', { name: 'Dismiss notification' }));

    await waitFor(() => expect(screen.queryByTestId('toast')).not.toBeInTheDocument());
  });

  it('useToast throws outside of a ToastProvider', () => {
    // Swallow the expected React error-boundary console noise for this one assertion.
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    function Lonely() {
      useToast();
      return null;
    }
    expect(() => render(<Lonely />)).toThrow('useToast must be used within a ToastProvider');
    consoleError.mockRestore();
  });
});

describe('toastFromError', () => {
  it('reads the title and detail off an ApiError problem', () => {
    const cause = new ApiError(422, {
      status: 422,
      title: 'Unpriceable currency switch',
      detail: 'No FX rate is available for INR to USD today.',
    });

    expect(toastFromError(cause, 'fallback')).toEqual({
      variant: 'error',
      title: 'Unpriceable currency switch',
      detail: 'No FX rate is available for INR to USD today.',
    });
  });

  it('falls back to a generic title for a non-ApiError failure', () => {
    expect(toastFromError(new TypeError('network down'), 'Could not reach the server.')).toEqual({
      variant: 'error',
      title: 'Could not reach the server.',
    });
  });
});
