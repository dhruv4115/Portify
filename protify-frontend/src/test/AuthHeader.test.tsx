import { render, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../App';

/**
 * A page load that already has a token in storage must still send it on the very first request.
 * The token provider is published by `AuthProvider`; React flushes a child's passive effects
 * before its parent's, so `PortfolioList`'s fetch effect runs first and would otherwise go out
 * unauthenticated — a deterministic 401 on every reload.
 */
describe('bearer token on first render', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it('sends Authorization when the token was already in storage', async () => {
    localStorage.setItem('protify.idToken', 'stored-token');

    const fetchMock = vi.fn().mockResolvedValue(
      new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } }),
    );
    vi.stubGlobal('fetch', fetchMock);

    render(<App />);

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());

    const headers = new Headers(fetchMock.mock.calls[0][1].headers);
    expect(headers.get('Authorization')).toBe('Bearer stored-token');
  });
});
