import { useEffect, useState } from 'react';
import { Cell, Pie, PieChart, ResponsiveContainer, Tooltip } from 'recharts';
import { getAllocation } from '../api/endpoints';
import type { Allocation, AllocationDimension, AllocationSlice, CurrencyCode } from '../api/types';
import { useI18n } from '../i18n/I18nProvider';
import { formatMoney } from '../lib/formatMoney';
import { SectionCard } from '../ui/Card';
import { EmptyState, ErrorState } from '../ui/Feedback';
import { Icon } from '../ui/Icon';
import { SelectInput } from '../ui/Field';
import { StaleBadge } from './StaleBadge';

const DIMENSIONS: AllocationDimension[] = ['CURRENCY', 'ASSET_TYPE', 'SECTOR', 'INSTRUMENT'];

/**
 * The validated categorical palette, in slot order.
 *
 * <p>These are CSS variables rather than hex literals, so each mode gets its own steps: the eight
 * hues are re-stepped for the dark surface rather than reused, which is what keeps every wedge
 * above 3:1 there. The order is fixed and never cycled — it is the colour-blind-safety mechanism,
 * checked for protanopia and deuteranopia separation against both of this app's surfaces, not a
 * decorative sequence.
 *
 * <p>Three light-mode slots sit below 3:1 on white by design. That is legal only because this
 * chart always ships the legend below with its labels and values visible — the legend *is* the
 * required relief channel, which is also why it is not collapsible.
 */
const SERIES = [
  'var(--series-1)',
  'var(--series-2)',
  'var(--series-3)',
  'var(--series-4)',
  'var(--series-5)',
  'var(--series-6)',
  'var(--series-7)',
  'var(--series-8)',
];

/** The key the server gives the folded slice. Fixed, so this file never pattern-matches a label. */
const OTHER_KEY = 'OTHER';

/**
 * Wedges to draw at most — the palette's own size, and the reason the fold exists.
 *
 * <p>`SERIES[index % SERIES.length]` used to run past the end of the palette and start again, so
 * a ninth sector was drawn in the first one's colour: two different wedges, one fill, on a chart
 * whose legend is the required relief channel for the three light-mode slots that sit under 3:1.
 * Recycling did not degrade that guarantee, it voided it. Capping the wedge count is what keeps
 * the palette a fixed assignment rather than a cycle.
 *
 * <p>Eight also happens to be about what the legend can show beside a 252px chart before the two
 * columns stop lining up — which is the symptom that prompted this, but not the reason for the
 * number.
 */
const MAX_WEDGES = SERIES.length;

/** The fold's own colour: deliberately not a palette slot. "Other" is not a category — giving it
 * one of the eight would imply it is a thing rather than the absence of one. */
const OTHER_FILL = 'var(--ink-3)';

function fillFor(slice: AllocationSlice, index: number): string {
  return slice.key === OTHER_KEY ? OTHER_FILL : SERIES[index];
}

interface Datum extends AllocationSlice {
  /** Geometry only, same rule as `PerformanceChart`: every figure a person reads comes from
   * `weightPct`/`value`, this number only places a wedge. */
  weight: number;
}

interface Props {
  portfolioId: number;
  currency: CurrencyCode;
  /** Bumped by the parent whenever a write (add/delete transaction) may have changed the mix. */
  version: number;
}

/**
 * day-4-dev-C.md D4-C2: the multi-currency feature's clearest picture. Defaults to `CURRENCY`
 * — not the backend's own `ASSET_TYPE` default (API_CONTRACT.md §13) — because a currency pie is
 * the one view a single-currency competitor cannot show at all.
 *
 * <p><b>At most {@link MAX_WEDGES} wedges, the rest folded into "Other".</b> By instrument, a
 * real portfolio has thirty of them; thirty wedges is an unreadable pie and a legend twice the
 * height of the chart it belongs to, which pushes the two columns out of alignment. The fold is
 * asked for from the server rather than done here, because it is a sum of money — see
 * `getAllocation`.
 *
 * <p>The folded rows are not thrown away. "Other" expands into the full list on demand, so
 * capping the picture never costs the reader the data — which matters most on the instrument
 * view, where the tail is the long half of the portfolio.
 */
export function AllocationChart({ portfolioId, currency, version }: Props) {
  const { t } = useI18n();
  const [by, setBy] = useState<AllocationDimension>('CURRENCY');
  const [allocation, setAllocation] = useState<Allocation | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [hovered, setHovered] = useState<string | null>(null);
  /** The unfolded slices, fetched only if the reader opens "Other". */
  const [foldedRows, setFoldedRows] = useState<AllocationSlice[] | null>(null);
  const [expanded, setExpanded] = useState(false);
  const [expanding, setExpanding] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    // Collapsed and discarded on every refetch: the tail belongs to the dimension and the
    // numbers it was fetched for, and neither survives a change to `by`, `currency` or `version`.
    setExpanded(false);
    setFoldedRows(null);
    getAllocation(portfolioId, { by, currency, limit: MAX_WEDGES })
      .then((next) => {
        if (!cancelled) {
          setAllocation(next);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setError(t('allocation.loadError'));
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
    // `t` is excluded deliberately: switching language must not refetch allocation.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [portfolioId, by, currency, version]);

  const slices = allocation?.slices ?? [];
  const other = slices.find((slice) => slice.key === OTHER_KEY) ?? null;

  /** Fetches the unfolded list the first time it is needed, then just toggles. */
  async function toggleOther() {
    if (expanded) {
      setExpanded(false);
      return;
    }
    if (foldedRows) {
      setExpanded(true);
      return;
    }
    setExpanding(true);
    try {
      const full = await getAllocation(portfolioId, { by, currency });
      // Everything the folded response did not name — matched by key, so this stays right even
      // if the two responses disagree about ordering.
      const named = new Set(slices.map((slice) => slice.key));
      setFoldedRows(full.slices.filter((slice) => !named.has(slice.key)));
      setExpanded(true);
    } catch {
      setError(t('allocation.loadError'));
    } finally {
      setExpanding(false);
    }
  }

  return (
    <SectionCard
      id="allocation"
      title={t('allocation.title')}
      icon="pie"
      actions={
        <>
          {allocation?.dataQuality.stale && <StaleBadge dataQuality={allocation.dataQuality} />}
          <SelectInput
            aria-label={t('allocation.groupBy')}
            value={by}
            onChange={(event) => setBy(event.target.value as AllocationDimension)}
            className="h-8 w-auto py-0 text-[0.8125rem]"
          >
            {DIMENSIONS.map((dimension) => (
              <option key={dimension} value={dimension}>
                {t(`allocation.by.${dimension}` as const)}
              </option>
            ))}
          </SelectInput>
        </>
      }
    >
      {loading && <div className="skeleton skeleton-chart" data-testid="allocation-skeleton" />}

      {!loading && error && <ErrorState title={error} />}

      {!loading && !error && allocation && slices.length === 0 && (
        <EmptyState icon="pie" testId="allocation-empty" title={t('allocation.empty')} />
      )}

      {/*
        A container query, not a viewport one. `lg:` asked the wrong question: this card sits in
        PortfolioDetail's `xl:grid-cols-2`, so crossing 1280px *halves* the card while a viewport
        breakpoint reads the screen as getting roomier and splits the inside into two columns. The
        legend column landed at ~160px for a row whose amount and percentage are `shrink-0` and
        need ~200px, so it overflowed its column — on a wider screen, which is backwards.

        `@xl` (36rem) is measured against this card, so the split happens when the *card* is wide
        enough for a 17rem chart and a legend that still has ~280px to lay out an amount in.
      */}
      {!loading && !error && allocation && slices.length > 0 && (
        <div className="@container">
          <div className="grid gap-6 @xl:grid-cols-[minmax(0,17rem)_1fr] @xl:items-center">
            <div className="relative chart" data-testid="allocation-chart">
              <ResponsiveContainer width="100%" height={252}>
                <PieChart>
                  <Pie
                    data={toData(slices)}
                    dataKey="weight"
                    nameKey="label"
                    innerRadius={72}
                    outerRadius={104}
                    // A 2px gap of the card's own surface between wedges. Adjacent fills that touch
                    // read as one shape at small sizes; the gap is what keeps them countable.
                    paddingAngle={1.5}
                    stroke="var(--surface)"
                    strokeWidth={2}
                    isAnimationActive={false}
                    onMouseEnter={(_, index) => setHovered(slices[index]?.key ?? null)}
                    onMouseLeave={() => setHovered(null)}
                  >
                    {slices.map((slice, index) => (
                      <Cell
                        key={slice.key}
                        fill={fillFor(slice, index)}
                        opacity={hovered && hovered !== slice.key ? 0.35 : 1}
                        style={{ transition: 'opacity 0.18s var(--ease-smooth)' }}
                      />
                    ))}
                  </Pie>
                  <Tooltip content={<SliceTooltip />} />
                </PieChart>
              </ResponsiveContainer>

              {/* The hole earns its keep: the total belongs at the centre of a part-to-whole chart,
                  not in a caption the eye has to travel to. */}
              <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
                <span className="text-[0.6875rem] font-semibold uppercase tracking-[0.07em] text-ink-3">
                  {t('allocation.total')}
                </span>
                <span className="mt-0.5 text-[1.0625rem] font-semibold tabular-nums tracking-[-0.015em] text-ink">
                  {formatMoney(allocation.total)}
                </span>
              </div>
            </div>

            <div>
              <ul className="legend space-y-1" data-testid="allocation-legend">
                {slices.map((slice, index) => {
                  const isOther = slice.key === OTHER_KEY;
                  return (
                    <li
                      key={slice.key}
                      onMouseEnter={() => setHovered(slice.key)}
                      onMouseLeave={() => setHovered(null)}
                      style={{ opacity: hovered && hovered !== slice.key ? 0.5 : 1 }}
                    >
                      <div className="flex items-center gap-2.5 rounded-lg px-2 py-1.5 transition-colors duration-150 hover:bg-surface-2">
                        <span
                          className="h-2.5 w-2.5 shrink-0 rounded-[3px]"
                          style={{ background: fillFor(slice, index) }}
                          aria-hidden="true"
                        />
                        {isOther ? (
                          // A button, not a row with a chevron glued on: the whole label is the
                          // control, so it is reachable by keyboard and announces its own state.
                          <button
                            type="button"
                            onClick={() => void toggleOther()}
                            aria-expanded={expanded}
                            disabled={expanding}
                            className="link-quiet flex min-w-0 flex-1 items-center gap-1 text-start text-[0.8125rem]"
                          >
                            <span className="truncate">
                              {t('allocation.other', { count: slice.instrumentCount })}
                            </span>
                            <Icon
                              name={expanded ? 'chevronDown' : 'chevronRight'}
                              size={13}
                              className="shrink-0 rtl-flip"
                            />
                          </button>
                        ) : (
                          <span className="min-w-0 flex-1 truncate text-[0.8125rem] text-ink-2">
                            {slice.label}
                          </span>
                        )}
                        <span className="shrink-0 text-[0.8125rem] tabular-nums text-ink-3">
                          {formatMoney(slice.value)}
                        </span>
                        <span className="w-14 shrink-0 text-end text-[0.8125rem] font-semibold tabular-nums text-ink">
                          {slice.weightPct}%
                        </span>
                      </div>

                      {isOther && expanded && foldedRows && (
                        // Indented and unswatched: these are not wedges of their own, they are what
                        // the one above is made of, and a colour chip would claim otherwise.
                        <ul className="mt-0.5 space-y-0.5 border-s border-hairline ps-3 ms-3" data-testid="allocation-other-rows">
                          {foldedRows.map((row) => (
                            <li key={row.key} className="flex items-center gap-2.5 rounded-lg px-2 py-1 text-[0.75rem]">
                              <span className="min-w-0 flex-1 truncate text-ink-3">{row.label}</span>
                              <span className="shrink-0 tabular-nums text-ink-3">{formatMoney(row.value)}</span>
                              <span className="w-14 shrink-0 text-end tabular-nums text-ink-2">{row.weightPct}%</span>
                            </li>
                          ))}
                        </ul>
                      )}
                    </li>
                  );
                })}
              </ul>

              {other && !expanded && (
                <p className="mt-1 px-2 text-[0.75rem] text-ink-3">
                  {t('allocation.otherHint', { count: other.instrumentCount })}
                </p>
              )}

              <p className="mt-3 flex items-start gap-1.5 border-t border-hairline-subtle pt-3 text-[0.75rem] text-ink-3">
                <Icon name="info" size={12} className="mt-0.5" />
                {t('allocation.cashExcluded')}
              </p>
            </div>
          </div>
        </div>
      )}
    </SectionCard>
  );
}

function SliceTooltip({ active, payload }: { active?: boolean; payload?: { payload: Datum }[] }) {
  if (!active || !payload?.length) {
    return null;
  }
  const slice = payload[0].payload;
  return (
    <div className="chart-tooltip">
      <div className="chart-tooltip-label">{slice.label}</div>
      <div className="chart-tooltip-value">
        {formatMoney(slice.value)} · {slice.weightPct}%
      </div>
    </div>
  );
}

function toData(slices: AllocationSlice[]): Datum[] {
  return slices.map((slice) => ({ ...slice, weight: Number(slice.weightPct) }));
}
