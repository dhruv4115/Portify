import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Insights } from '../api/types';
import { getInsights } from '../api/endpoints';
import { InsightsCard } from '../components/InsightsCard';

vi.mock('../api/endpoints', () => ({
  getInsights: vi.fn(),
}));

describe('InsightsCard', () => {
  beforeEach(() => {
    vi.mocked(getInsights).mockReset();
  });

  it('shows a skeleton while loading', () => {
    vi.mocked(getInsights).mockReturnValue(new Promise<Insights>(() => {}));

    render(<InsightsCard portfolioId={7} version={0} />);

    expect(screen.getByTestId('insights-skeleton')).toBeInTheDocument();
  });

  it('renders the AI-generated label honestly when the engine used the LLM path', async () => {
    vi.mocked(getInsights).mockResolvedValue({
      engine: 'AI_GENERATED',
      summary: 'Your portfolio is up 10% this month, led by RELIANCE.',
      generatedAt: '2026-07-19T00:00:00Z',
    });

    render(<InsightsCard portfolioId={7} version={0} />);

    await waitFor(() => expect(screen.getByTestId('insights-card')).toBeInTheDocument());
    expect(screen.getByTestId('insights-engine')).toHaveTextContent('AI-generated');
    expect(screen.getByText(/up 10% this month/)).toBeInTheDocument();
  });

  it('renders the rule-based label honestly when the LLM path was off or failed', async () => {
    vi.mocked(getInsights).mockResolvedValue({
      engine: 'RULE_BASED',
      summary: 'Your largest holding is RELIANCE at 40% of the portfolio.',
      generatedAt: '2026-07-19T00:00:00Z',
    });

    render(<InsightsCard portfolioId={7} version={0} />);

    await waitFor(() => expect(screen.getByTestId('insights-card')).toBeInTheDocument());
    expect(screen.getByTestId('insights-engine')).toHaveTextContent('Rule-based summary');
  });

  it('shows an empty state, not an error, when the endpoint is unreachable', async () => {
    vi.mocked(getInsights).mockRejectedValue(new Error('404'));

    render(<InsightsCard portfolioId={7} version={0} />);

    await waitFor(() => expect(screen.getByTestId('insights-empty')).toBeInTheDocument());
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  // ---- switching between the two readings -----------------------------------------------

  const BOTH: Insights = {
    engine: 'AI_GENERATED',
    summary: 'Your portfolio is up 10% this month, led by RELIANCE.',
    generatedAt: '2026-07-19T00:00:00Z',
    variants: [
      { engine: 'AI_GENERATED', summary: 'Your portfolio is up 10% this month, led by RELIANCE.' },
      { engine: 'RULE_BASED', summary: 'RELIANCE is 62.0% of market value.' },
    ],
  };

  it('shows the AI reading by default when both were returned', async () => {
    vi.mocked(getInsights).mockResolvedValue(BOTH);

    render(<InsightsCard portfolioId={7} version={0} />);

    await waitFor(() => expect(screen.getByTestId('insights-card')).toBeInTheDocument());
    expect(screen.getByTestId('insights-card')).toHaveAttribute('data-engine', 'AI_GENERATED');
    expect(screen.getByText(/up 10% this month/)).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'AI' })).toBeChecked();
  });

  it('swaps to the rule-based reading when the toggle is used', async () => {
    vi.mocked(getInsights).mockResolvedValue(BOTH);
    const user = userEvent.setup();

    render(<InsightsCard portfolioId={7} version={0} />);
    await waitFor(() => expect(screen.getByTestId('insights-card')).toBeInTheDocument());
    await user.click(screen.getByRole('radio', { name: 'Rule-based' }));

    expect(screen.getByTestId('insights-card')).toHaveAttribute('data-engine', 'RULE_BASED');
    expect(screen.getByText('RELIANCE is 62.0% of market value.')).toBeInTheDocument();
    expect(screen.queryByText(/up 10% this month/)).not.toBeInTheDocument();
    // No second request — both readings arrived together.
    expect(getInsights).toHaveBeenCalledTimes(1);
  });

  /** A toggle with one position claims a choice the reader does not have. */
  it('keeps the plain badge when only one reading was returned', async () => {
    vi.mocked(getInsights).mockResolvedValue({
      engine: 'RULE_BASED',
      summary: 'RELIANCE is 62.0% of market value.',
      generatedAt: '2026-07-19T00:00:00Z',
      variants: [{ engine: 'RULE_BASED', summary: 'RELIANCE is 62.0% of market value.' }],
    });

    render(<InsightsCard portfolioId={7} version={0} />);

    await waitFor(() => expect(screen.getByTestId('insights-card')).toBeInTheDocument());
    expect(screen.getByTestId('insights-engine')).toHaveTextContent('Rule-based summary');
    expect(screen.queryByRole('radiogroup')).not.toBeInTheDocument();
  });

  it('falls back to the top-level fields when the server sends no variants at all', async () => {
    vi.mocked(getInsights).mockResolvedValue({
      engine: 'AI_GENERATED',
      summary: 'Your portfolio is up 10% this month, led by RELIANCE.',
      generatedAt: '2026-07-19T00:00:00Z',
    });

    render(<InsightsCard portfolioId={7} version={0} />);

    await waitFor(() => expect(screen.getByTestId('insights-card')).toBeInTheDocument());
    expect(screen.getByTestId('insights-engine')).toHaveTextContent('AI-generated');
    expect(screen.getByText(/up 10% this month/)).toBeInTheDocument();
  });

  /** Holding the choice across a refetch would pin a reader to rule-based on a portfolio whose
   * AI summary has since started working. */
  it('returns to the default reading after the data reloads', async () => {
    vi.mocked(getInsights).mockResolvedValue(BOTH);
    const user = userEvent.setup();

    const { rerender } = render(<InsightsCard portfolioId={7} version={0} />);
    await waitFor(() => expect(screen.getByTestId('insights-card')).toBeInTheDocument());
    await user.click(screen.getByRole('radio', { name: 'Rule-based' }));
    expect(screen.getByTestId('insights-card')).toHaveAttribute('data-engine', 'RULE_BASED');

    rerender(<InsightsCard portfolioId={7} version={1} />);

    await waitFor(() =>
      expect(screen.getByTestId('insights-card')).toHaveAttribute('data-engine', 'AI_GENERATED'),
    );
  });
});
