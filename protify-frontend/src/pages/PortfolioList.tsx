import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { getPortfolios } from '../api/endpoints';
import type { Portfolio } from '../api/types';
import { CreatePortfolioForm } from '../components/CreatePortfolioForm';
import { PortfolioCard, PortfolioRow } from '../components/PortfolioCard';
import { useToast } from '../components/Toast';
import { useI18n } from '../i18n/I18nProvider';
import { Page, PageHeader } from '../layout/AppShell';
import { compareAmounts, comparePercent } from '../lib/money';
import { Button } from '../ui/Button';
import { Card } from '../ui/Card';
import { EmptyState, ErrorState, Skeleton } from '../ui/Feedback';
import { SearchInput } from '../ui/Field';
import { Icon } from '../ui/Icon';
import { Segmented } from '../ui/Segmented';

type SortKey = 'name' | 'value' | 'pnl' | 'holdings';
type ViewMode = 'grid' | 'list';

export function PortfolioList() {
  const navigate = useNavigate();
  const { showToast } = useToast();
  const { t } = useI18n();
  const [searchParams, setSearchParams] = useSearchParams();

  const [portfolios, setPortfolios] = useState<Portfolio[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // `?new=1` lets the command palette and the overview's empty state deep-link straight into the
  // create form instead of landing the user on a list and asking them to find the button.
  const [creating, setCreating] = useState(() => searchParams.get('new') === '1');
  const [query, setQuery] = useState('');
  const [sort, setSort] = useState<SortKey>('value');
  const [view, setView] = useState<ViewMode>('grid');

  useEffect(() => {
    getPortfolios()
      .then(setPortfolios)
      .catch(() => setError(t('portfolios.loadError')))
      .finally(() => setLoading(false));
    // Loaded once on mount, as before. `t` is intentionally not a dependency: a language switch
    // must not re-issue the request, and any error text already on screen is re-rendered anyway.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function closeCreate() {
    setCreating(false);
    if (searchParams.has('new')) {
      searchParams.delete('new');
      setSearchParams(searchParams, { replace: true });
    }
  }

  // Straight to the new portfolio rather than back to the list: an empty portfolio is a dead end
  // until it has a transaction, and the form that adds one lives on the detail page.
  function created(portfolio: Portfolio) {
    closeCreate();
    showToast({ variant: 'success', title: t('createPortfolio.created', { name: portfolio.name }) });
    navigate(`/portfolios/${portfolio.id}`);
  }

  const visible = useMemo(() => {
    const needle = query.trim().toLowerCase();
    const filtered = needle
      ? portfolios.filter((portfolio) => portfolio.name.toLowerCase().includes(needle))
      : portfolios;

    return [...filtered].sort((left, right) => {
      switch (sort) {
        case 'name':
          return left.name.localeCompare(right.name);
        // Exact decimal comparison, never a parsed float. Across currencies this orders by raw
        // magnitude rather than by worth — it is a sort order, not a claim of equivalence.
        case 'value':
          return compareAmounts(right.totalValue.amount, left.totalValue.amount);
        // A percentage is the one figure that compares honestly across currencies.
        case 'pnl':
          return comparePercent(right.unrealisedPnlPct, left.unrealisedPnlPct);
        case 'holdings':
          return right.holdingCount - left.holdingCount;
      }
    });
  }, [portfolios, query, sort]);

  const hasPortfolios = portfolios.length > 0;

  return (
    <Page>
      <PageHeader
        title={t('portfolios.title')}
        description={t('portfolios.subtitle')}
        actions={
          !creating && (
            <Button variant="primary" icon="plus" onClick={() => setCreating(true)}>
              {t('portfolios.new')}
            </Button>
          )
        }
      />

      {creating && (
        <div className="mb-6 animate-rise-in">
          <CreatePortfolioForm onCreated={created} onCancel={closeCreate} />
        </div>
      )}

      {!loading && !error && hasPortfolios && (
        <div className="mb-5 flex flex-wrap items-center gap-3">
          <SearchInput
            id="portfolio-search"
            value={query}
            onValueChange={setQuery}
            label={t('portfolios.search')}
            clearLabel={t('portfolios.clearSearch')}
            placeholder={t('portfolios.searchPlaceholder')}
            className="w-full sm:w-72"
          />

          <div className="flex flex-wrap items-center gap-2 sm:ms-auto">
            <Segmented
              label={t('portfolios.sortBy')}
              value={sort}
              onChange={setSort}
              options={[
                { value: 'value', label: t('portfolios.sort.value') },
                { value: 'pnl', label: t('portfolios.sort.pnl') },
                { value: 'name', label: t('portfolios.sort.name') },
                { value: 'holdings', label: t('portfolios.sort.holdings') },
              ]}
            />
            <div className="segmented" role="group" aria-label={t('portfolios.view.grid')}>
              <button
                type="button"
                aria-pressed={view === 'grid'}
                aria-label={t('portfolios.view.grid')}
                onClick={() => setView('grid')}
                className="segmented-item px-2 py-1"
              >
                <Icon name="grid" size={14} />
              </button>
              <button
                type="button"
                aria-pressed={view === 'list'}
                aria-label={t('portfolios.view.list')}
                onClick={() => setView('list')}
                className="segmented-item px-2 py-1"
              >
                <Icon name="list" size={14} />
              </button>
            </div>
          </div>
        </div>
      )}

      {loading && (
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3" aria-busy="true">
          {[0, 1, 2].map((index) => (
            <Card key={index}>
              <Skeleton className="h-4 w-28" />
              <Skeleton className="mt-4 h-8 w-36" />
              <Skeleton className="mt-3 h-3 w-24" />
            </Card>
          ))}
        </div>
      )}

      {error && <ErrorState title={error} />}

      {!loading && !error && !creating && !hasPortfolios && (
        <Card>
          <EmptyState
            icon="wallet"
            title={t('portfolios.empty')}
            body={t('portfolios.emptyHint')}
            action={
              <Button variant="link" onClick={() => setCreating(true)}>
                {t('portfolios.createFirst')}
              </Button>
            }
          />
        </Card>
      )}

      {!loading && !error && hasPortfolios && visible.length === 0 && (
        <Card>
          <EmptyState
            icon="search"
            inset
            title={t('portfolios.noMatches', { query })}
            action={
              <Button onClick={() => setQuery('')} icon="x">
                {t('portfolios.clearSearch')}
              </Button>
            }
          />
        </Card>
      )}

      {visible.length > 0 &&
        (view === 'grid' ? (
          <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
            {visible.map((portfolio, index) => (
              <PortfolioCard key={portfolio.id} portfolio={portfolio} index={index} />
            ))}
          </div>
        ) : (
          <div className="space-y-2">
            {visible.map((portfolio, index) => (
              <PortfolioRow key={portfolio.id} portfolio={portfolio} index={index} />
            ))}
          </div>
        ))}

      {visible.length > 0 && (
        <p className="mt-6 text-[0.75rem] text-ink-3">
          {visible.length === 1 ? t('portfolios.countOne') : t('portfolios.count', { count: visible.length })}
        </p>
      )}
    </Page>
  );
}
