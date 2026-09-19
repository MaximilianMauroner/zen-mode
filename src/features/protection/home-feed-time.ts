import type { ZenGuardStatus } from '../../../modules/zen-guard/src/ZenGuardModule';
import { getSupportedAppAvailability } from './target-availability.ts';
import { getXFeedReadiness } from './x-readiness.ts';

type HomeFeed = 'instagram' | 'x';

/** Human-readable time from the latest accessibility-service policy snapshot. */
export function getHomeFeedTimeLabel(status: ZenGuardStatus | null, feed: HomeFeed): string | null {
  if (!status?.available || !status.serviceEnabled || !status.protectionEnabled) return null;
  const app = feed === 'instagram' ? 'instagram' : 'x';
  if (getSupportedAppAvailability(status, app) !== 'installed') return null;
  if (feed === 'instagram' && status.instagramObservationMode) return null;
  // A feed that is not enforcing has no allowance to report.
  if (feed === 'x' && (status.xObservationMode || getXFeedReadiness(status, 'home') !== 'ready')) return null;
  if (feed === 'x' && status.xHomeUsageState === 'unknown') return 'Time unavailable';

  const allowanceMinutes = feed === 'instagram' ? status.instagramHomeMinutes : status.xHomeMinutes;
  const usedMs = feed === 'instagram' ? status.instagramHomeUsedMs : status.xHomeUsedMs;
  const breakRemainingMs = feed === 'instagram' ? status.instagramHomeBreakRemainingMs : status.xHomeBreakRemainingMs;

  if (breakRemainingMs > 0) return `Available again in ${formatDuration(breakRemainingMs)}`;

  const remainingMs = Math.max(0, allowanceMinutes * 60_000 - usedMs);
  if (remainingMs <= 0) return 'No time left';
  return `${formatDuration(remainingMs)} left`;
}

function formatDuration(durationMs: number): string {
  const minutes = Math.max(1, Math.ceil(durationMs / 60_000));
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return rest === 0 ? `${hours}h` : `${hours}h ${rest}m`;
}
