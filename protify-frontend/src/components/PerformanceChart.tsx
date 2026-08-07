import {
  Area,
  AreaChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import type { Money, Performance, PerformanceInterval } from '../api/types';
import { useI18n } from '../i18n/I18nProvider';
import { formatMoney, formatPercent, isNegative } from '../lib/formatMoney';
import { formatAxisDate, formatFullDate, INTERVALS, RANGE_KEYS, type RangeKey } from '../lib/ranges';
import { SectionCard } from '../ui/Card';
import { EmptyState } from '../ui/Feedback';
import { Icon } from '../ui/Icon';
import { Segmented } from '../ui/Segmented';
import { StatTile } from '../ui/Stat';
import { cx } from '../ui/cx';
import { StaleBadge } from './StaleBadge';

interface Props {
  performance: Performance | null;
  loading?: boolean;
  /** Range and interval are optional: without them the chart renders exactly one, fixed series. */
  range?: RangeKey;
  onRangeChange?: (next: RangeKey) => void;
  interval?: PerformanceInterval;
  onIntervalChange?: (next: PerformanceInterval) => void;
  /** A range change refetches without tearing the chart down — this dims it instead. */
  refreshing?: boolean;
}

interface Datum {
  date: string;
  /** Geometry only — see the note in {@link toData}. */
  value: number;
  total: Money;
  filled: boolean;
}

/**
 * Customer priority 2, and the one thing PLAN.md §9 says never to cut: total portfolio value over
 * time, in the portfolio's base currency.
 *
 * <p>Three states have to look deliberate rather than broken, because all three happen on a real
 * account: no points at all (a portfolio with no transactions yet), exactly one point (a
 * portfolio opened today — a line through one point draws nothing, so the point itself is
 * marked), and a full year of them.
 *
 * <p>Drawn as one filled area rather than several lines. A single series needs no legend — the
 * card's own title names it — and one measure on one axis is the whole reason this chart is
 * readable at a glance. A second series in different units would need its own chart, never a
 * second y-axis.
 */
export function PerformanceChart({
  performance,
  loading = false,
  range,
  onRangeChange,
  interval,
  onIntervalChange,
  refreshing = false,
}: Props) {
  const { t, meta } = useI18n();

  const controls =
    range && onRangeChange ? (
      <>
        <Segmented
          label={t('performance.range')}
          value={range}
          onChange={onRangeChange}
          size="sm"
          options={RANGE_KEYS.map((key) => ({
            value: key,
            label: t(`performance.range.${key}` as const),
          }))}
        />
        {interval && onIntervalChange && (
          <Segmented
            label={t('performance.interval')}
            value={interval}
            onChange={onIntervalChange}
            size="sm"
            className="hidden sm:inline-flex"
            options={INTERVALS.map((key) => ({
              value: key,
              label: t(`performance.interval.${key}` as const),
            }))}
          />
        )}
      </>
    ) : null;

  if (loading) {
    return (
      <SectionCard id="performance" title={t('performance.title')} icon="activity" busy actions={controls}>
        <div className="skeleton skeleton-chart" data-testid="chart-skeleton" />
      </SectionCard>
    );
  }

  const points = performance?.points ?? [];

  if (points.length === 0) {
    return (
      <SectionCard id="performance" title={t('performance.title')} icon="activity" actions={controls}>
        <EmptyState icon="activity" testId="chart-empty" title={t('performance.empty')} />
      </SectionCard>
    );
  }

  const summary = performance!.summary;
  const negative = isNegative(summary.absoluteChange);
  const data = toData(performance!);
  const single = data.length === 1;
  const stroke = negative ? 'var(--neg)' : 'var(--pos)';

  return (
    <SectionCard
      id="performance"
      title={t('performance.title')}
      icon="activity"
      actions={
        <>
          {performance!.dataQuality.stale && <StaleBadge dataQuality={performance!.dataQuality} />}
          {controls}
        </>
      }
      footer={
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
          <StatTile label={t('performance.startValue')} value={formatMoney(summary.startValue)} />
          <StatTile label={t('performance.endValue')} value={formatMoney(summary.endValue)} />
          <StatTile
            label={t('performance.netContributions')}
            value={formatMoney(summary.netContributions)}
            hint={t('performance.netContributionsHint')}
          />
          <StatTile
            label={t('portfolio.unrealisedPnl')}
            value={
              <span className={cx('inline-flex items-center gap-1', negative ? 'text-neg' : 'text-pos')}>
                <Icon name={negative ? 'trendDown' : 'trendUp'} size={14} strokeWidth={2.1} />
                {/* One text node: the amount and its percentage are a single phrase. */}
                {`${formatMoney(summary.absoluteChange)} (${formatPercent(summary.percentChange)})`}
              </span>
            }
          />
        </div>
      }
    >
      <div
        className={cx('chart transition-opacity duration-200', refreshing && 'opacity-50')}
        data-testid="performance-chart"
        data-points={data.length}
      >
        <ResponsiveContainer width="100%" height={300}>
          <AreaChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
            <defs>
              {/* The fill fades to nothing rather than stopping at a hard edge, so the area reads
                  as a magnitude under the line instead of a second solid shape. */}
              <linearGradient id="performance-fill" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor={stroke} stopOpacity={0.22} />
                <stop offset="100%" stopColor={stroke} stopOpacity={0} />
              </linearGradient>
            </defs>

            {/* Horizontal only, and hairline: gridlines are a reading aid, not part of the data. */}
            <CartesianGrid stroke="var(--grid)" strokeDasharray="0" vertical={false} />
            <XAxis
              dataKey="date"
              tick={{ fontSize: 11, fill: 'var(--axis)' }}
              tickFormatter={(value: string) => formatAxisDate(value, meta.locale, data.length)}
              tickLine={false}
              axisLine={{ stroke: 'var(--grid)' }}
              minTickGap={28}
              dy={6}
            />
            <YAxis
              tick={{ fontSize: 11, fill: 'var(--axis)' }}
              tickLine={false}
              axisLine={false}
              width={64}
              domain={['auto', 'auto']}
              tickFormatter={(value: number) => compactAxisValue(value, meta.locale)}
            />
            <Tooltip
              content={<ValueTooltip locale={meta.locale} carriedForward={t('performance.carriedForward')} />}
              cursor={{ stroke: 'var(--ink-3)', strokeWidth: 1, strokeDasharray: '4 4' }}
            />
            <Area
              type="monotone"
              dataKey="value"
              name={t('performance.totalValue')}
              stroke={stroke}
              strokeWidth={2}
              fill="url(#performance-fill)"
              // A single point has no line to draw, so its marker has to be visible; a long
              // series with a marker per day is unreadable.
              dot={single ? { r: 5, fill: stroke, strokeWidth: 0 } : data.length <= 30}
              activeDot={{ r: 5, strokeWidth: 2, stroke: 'var(--surface)' }}
              isAnimationActive={false}
            />
          </AreaChart>
        </ResponsiveContainer>
      </div>

      {single && (
        <p className="mt-3 flex items-center gap-1.5 text-[0.8125rem] text-ink-3" data-testid="chart-single-point">
          <Icon name="info" size={13} />
          {t('performance.singlePoint')}
        </p>
      )}
    </SectionCard>
  );
}

function ValueTooltip({
  active,
  payload,
  locale,
  carriedForward,
}: {
  active?: boolean;
  payload?: { payload: Datum }[];
  locale: string;
  carriedForward: string;
}) {
  if (!active || !payload?.length) {
    return null;
  }
  const datum = payload[0].payload;
  return (
    <div className="chart-tooltip">
      <div className="chart-tooltip-label">{formatFullDate(datum.date, locale)}</div>
      <div className="chart-tooltip-value">{formatMoney(datum.total)}</div>
      {datum.filled && <div className="chart-tooltip-note">{carriedForward}</div>}
    </div>
  );
}

/**
 * `Number(...)` appears here and nowhere else in the app. A chart axis is a pixel position, and
 * SVG cannot be given a decimal string — but the number is used only to place the line. Every
 * figure the user actually reads comes from `total`, the server's own string, so no displayed
 * amount is ever routed through a double.
 */
function toData(performance: Performance): Datum[] {
  return performance.points.map((point) => ({
    date: point.date,
    value: Number(point.totalValue.amount),
    total: point.totalValue,
    filled: point.filled,
  }));
}

/**
 * Axis ticks only. Derived from the same geometry-only number as the line, never shown as a
 * figure the user might quote — the tooltip and the summary carry the exact amounts.
 */
function compactAxisValue(value: number, locale: string): string {
  return new Intl.NumberFormat(locale, { notation: 'compact', maximumFractionDigits: 1 }).format(value);
}
