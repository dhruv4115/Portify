import type { DataQuality } from '../api/types';
import { useI18n } from '../i18n/I18nProvider';
import { Icon } from '../ui/Icon';

/**
 * The visible face of the offline-safety rule (§0.5): when a price or rate is old the UI says so
 * in amber and keeps rendering. It never blocks, and it is never an error — degraded data shown
 * honestly beats a blank screen, which is the whole point of the fallback chain behind it.
 *
 * <p>The warning icon is not decoration. Amber against a light surface sits below the 3:1 contrast
 * a colour-only signal would need, so the icon and the word carry the meaning and the colour only
 * reinforces it.
 */
export function StaleBadge({ dataQuality }: { dataQuality: DataQuality }) {
  const { t } = useI18n();

  if (!dataQuality.stale) {
    return null;
  }

  const asOf = dataQuality.priceAsOf ?? dataQuality.rateAsOf;

  return (
    <span
      className="badge badge-stale"
      title={asOf ? t('quality.pricedOn', { date: asOf }) : t('quality.pricedUnknown')}
      data-testid="stale-badge"
    >
      <Icon name="alert" size={10} strokeWidth={2.2} />
      {asOf ? t('quality.stale', { date: asOf }) : t('quality.unpriced')}
    </span>
  );
}
