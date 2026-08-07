import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import * as endpoints from '../api/endpoints';
import { ToastProvider } from '../components/Toast';
import { PortfolioList } from '../pages/PortfolioList';

vi.mock('../api/endpoints');

const navigate = vi.fn();
vi.mock('react-router-dom', async () => ({
  ...(await vi.importActual<typeof import('react-router-dom')>('react-router-dom')),
  useNavigate: () => navigate,
}));

function renderList() {
  return render(
    <MemoryRouter>
      <ToastProvider>
        <PortfolioList />
      </ToastProvider>
    </MemoryRouter>,
  );
}

describe('creating a portfolio', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(endpoints.getPortfolios).mockResolvedValue([]);
  });

  it('offers a way out of the empty state', async () => {
    renderList();
    await screen.findByText(/No portfolios yet/);

    expect(screen.getByRole('button', { name: 'Create your first one' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'New portfolio' })).toBeInTheDocument();
  });

  it('posts the portfolio and navigates to it', async () => {
    const user = userEvent.setup();
    vi.mocked(endpoints.createPortfolio).mockResolvedValue({
      id: 7,
      name: 'Retirement',
      baseCurrency: 'EUR',
    } as never);

    renderList();
    await user.click(await screen.findByRole('button', { name: 'Create your first one' }));

    await user.type(screen.getByLabelText('Name'), 'Retirement');
    await user.selectOptions(screen.getByLabelText('Base currency'), 'EUR');
    await user.click(screen.getByRole('button', { name: 'Create portfolio' }));

    await waitFor(() =>
      expect(endpoints.createPortfolio).toHaveBeenCalledWith({
        name: 'Retirement',
        baseCurrency: 'EUR',
      }),
    );
    expect(navigate).toHaveBeenCalledWith('/portfolios/7');
  });

  it('shows the duplicate-name conflict, which carries no field to blame', async () => {
    const user = userEvent.setup();
    vi.mocked(endpoints.createPortfolio).mockRejectedValue(
      new ApiError(409, {
        status: 409,
        title: 'Conflict',
        detail: "A portfolio named 'Retirement' already exists.",
      }),
    );

    renderList();
    await user.click(await screen.findByRole('button', { name: 'Create your first one' }));
    await user.type(screen.getByLabelText('Name'), 'Retirement');
    await user.click(screen.getByRole('button', { name: 'Create portfolio' }));

    expect(await screen.findByTestId('form-error')).toHaveTextContent(
      "A portfolio named 'Retirement' already exists.",
    );
    expect(navigate).not.toHaveBeenCalled();
  });
});
