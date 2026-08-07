import { useEffect, useState } from 'react';
import { getInsights } from '../api/endpoints';
import type { Insights, InsightsEngine, InsightsVariant } from '../api/types';
import { useI18n } from '../i18n/I18nProvider';
import { formatFullDate } from '../lib/ranges';
import { SectionCard } from '../ui/Card';
import { EmptyState, Skeleton } from '../ui/Feedback';
import { Icon } from '../ui/Icon';
import { Segmented } from '../ui/Segmented';

interface Props {
  portfolioId: number;
  /** Bumped by the parent whenever a write may have changed the numbers behind the summary. */
  version: number;
}

/**
 * day-5-dev-C.md D5-C2. Dev B's endpoint does not exist in the backend yet (see {@link Insights}
 * in `api/types.ts`), so a failed request here — a 404 today — renders the same empty state a
 * genuinely-empty-but-reachable endpoint would, rather than an error toast: the offline/not-yet
 * path is meant to look deliberate, not broken, same as an honestly-labelled rule-based summary
 * would once the endpoint exists.
 *
 * <p>The engine badge is shown rather than hidden. "Rule-based" is not a degraded mode to
 * apologise for — it means the deterministic path produced this sentence, and a reader deserves
 * to know which one did.
 *
 * <p>When the server returns both readings, the badge becomes a switch between them, defaulting
 * to the AI one. It appears <em>only</em> when there are genuinely two: a single-variant response
 * keeps the plain badge, because a toggle with one position claims a choice the reader does not
 * have. Both variants describe the same snapshot — the server generates them together for
 * exactly that reason — so a difference between them is a difference between the engines, which
 * is the only thing worth comparing.
 */
export function InsightsCard({ portfolioId, version }: Props) {
  const { t, meta } = useI18n();
  const [insights, setInsights] = useState<Insights | null>(null);
  const [loading, setLoading] = useState(true);
  const [available, setAvailable] = useState(true);
  const [selected, setSelected] = useState<InsightsEngine | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    getInsights(portfolioId)
      .then((next) => {
        if (!cancelled) {
          setInsights(next);
          setAvailable(true);
          // Back to the default on every reload. Holding the previous choice across a refetch
          // would silently pin the reader to rule-based on a portfolio whose AI summary has
          // since started working.
          setSelected(null);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setAvailable(false);
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
  }, [portfolioId, version]);

  if (loading) {
    return (
      <SectionCard id="insights" title={t('insights.title')} icon="sparkles" busy>
        <div data-testid="insights-skeleton" className="space-y-2">
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-4 w-[82%]" />
        </div>
      </SectionCard>
    );
  }

  if (!available || !insights) {
    return (
      <SectionCard id="insights" title={t('insights.title')} icon="sparkles">
        <EmptyState icon="sparkles" inset testId="insights-empty" title={t('insights.empty')} />
      </SectionCard>
    );
  }

  const variants = variantsOf(insights);
  const shown = variants.find((variant) => variant.engine === selected) ?? variants[0];
  const aiGenerated = shown.engine === 'AI_GENERATED';

  return (
    <SectionCard
      id="insights"
      title={t('insights.title')}
      icon="sparkles"
      actions={
        variants.length > 1 ? (
          <Segmented
            size="sm"
            label={t('insights.engineLabel')}
            value={shown.engine}
            onChange={setSelected}
            options={variants.map((variant) => ({
              value: variant.engine,
              label: t(`insights.engine.short.${variant.engine}`),
              title: t(`insights.engine.${variant.engine}`),
            }))}
          />
        ) : (
          <span className={`badge ${aiGenerated ? 'badge-brand' : ''}`} data-testid="insights-engine">
            <Icon name={aiGenerated ? 'sparkles' : 'shield'} size={11} />
            {t(aiGenerated ? 'insights.engine.AI_GENERATED' : 'insights.engine.RULE_BASED')}
          </span>
        )
      }
      className="relative overflow-hidden"
      footer={
        <p className="flex items-center gap-1.5 text-[0.75rem] text-ink-3">
          <Icon name="clock" size={12} />
          {t('insights.generatedAt', { when: formatFullDate(insights.generatedAt.slice(0, 10), meta.locale) })}
        </p>
      }
    >
      <div data-testid="insights-card" data-engine={shown.engine}>
        <p className="text-[0.9375rem] leading-relaxed text-ink-2">{shown.summary}</p>
      </div>
    </SectionCard>
  );
}

/**
 * The readings to offer, from a server that may or may not send `variants`.
 *
 * <p>An older backend sends only `engine`/`summary`; treating that as one variant keeps this
 * component working against both, and means the toggle appears exactly when a second reading
 * genuinely exists rather than whenever the field happens to be present.
 */
function variantsOf(insights: Insights): InsightsVariant[] {
  return insights.variants?.length
    ? insights.variants
    : [{ engine: insights.engine, summary: insights.summary }];
}
