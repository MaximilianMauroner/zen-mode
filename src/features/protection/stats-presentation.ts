import type { EnforcementStats } from './native';

export const ENFORCEMENT_STAT_CATEGORIES = [
  { key: 'youtube_shorts', label: 'YouTube Shorts', detail: 'Shorts visits ended after the first video.' },
  { key: 'instagram_reels', label: 'Instagram Reels', detail: 'Reels entries or swipes stopped.' },
  { key: 'instagram_home', label: 'Instagram home', detail: 'Home-feed limits reached.' },
  { key: 'instagram_explore', label: 'Instagram Explore', detail: 'Explore was closed by the rule.' },
  { key: 'x_home', label: 'X home feed', detail: 'Home-feed time limits reached.' },
  { key: 'x_videos', label: 'X videos', detail: 'The next video was exited.' },
  { key: 'tiktok_feed', label: 'TikTok feed', detail: 'TikTok feed interventions when enabled.' },
  { key: 'blocked_site', label: 'Blocked sites', detail: 'A matched site was covered.' },
  { key: 'app_limit', label: 'App limits', detail: 'A daily app limit sent the app home.' },
  { key: 'rolling_limit', label: 'Rolling app limits', detail: 'A rolling allowance sent the app home.' },
  { key: 'timed_visit', label: 'Timed visits', detail: 'A timed-visit boundary appeared or ended a visit.' },
] as const;

export type EnforcementStatKey = (typeof ENFORCEMENT_STAT_CATEGORIES)[number]['key'];

export function getStatCount(stats: EnforcementStats, key: string): number {
  return normalizeCount(stats.counts[key]);
}

export function getOtherStatCount(stats: EnforcementStats): number {
  const known = new Set<string>(ENFORCEMENT_STAT_CATEGORIES.map(({ key }) => key));
  return Object.entries(stats.counts)
    .filter(([key]) => !known.has(key))
    .reduce((total, [, value]) => total + normalizeCount(value), 0);
}

export function normalizeCount(value: number | undefined): number {
  return Number.isFinite(value) ? Math.max(0, Math.trunc(value ?? 0)) : 0;
}

export function formatStatCount(value: number | undefined): string {
  return normalizeCount(value).toLocaleString('en-US');
}
