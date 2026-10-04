import type { EnforcementStats } from './native';

import {
  ENFORCEMENT_STAT_CATEGORIES,
  formatStatCount,
  getOtherStatCount,
  getStatCount,
} from './stats-presentation.ts';

export type StatsScreenRow = {
  key: string;
  label: string;
  detail: string;
  value: string;
};

export type StatsScreenModel = {
  total: string;
  rows: StatsScreenRow[];
  other: StatsScreenRow | null;
};

/** Keep interface previews from presenting synthetic web counts as device data. */
export function getStatsScreenState(platform: string, stats: EnforcementStats | null, loading: boolean) {
  if (platform === 'web') {
    return { available: false, model: null, message: 'Statistics are available in the Android app.' };
  }

  if (stats) return { available: true, model: getStatsScreenModel(stats), message: '' };
  return { available: true, model: null, message: loading ? 'Loading statistics…' : 'No statistics available.' };
}

/** The exact aggregate values rendered by the /stats protection rows. */
export function getStatsScreenModel(stats: EnforcementStats): StatsScreenModel {
  const other = getOtherStatCount(stats);

  return {
    total: formatStatCount(stats.total),
    rows: ENFORCEMENT_STAT_CATEGORIES.map(({ key, label, detail }) => ({
      key,
      label,
      detail,
      value: formatStatCount(getStatCount(stats, key)),
    })),
    other: other > 0
      ? {
          key: 'other',
          label: 'Other',
          detail: 'Additional fixed enforcement reasons.',
          value: formatStatCount(other),
        }
      : null,
  };
}
