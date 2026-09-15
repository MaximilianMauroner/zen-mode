import type { ZenGuardStatus } from '../../../modules/zen-guard/src/ZenGuardModule';

type HomeFeed = 'instagram' | 'x';

/** Human-readable time from the latest accessibility-service policy snapshot. */
export function getHomeFeedTimeLabel(status: ZenGuardStatus | null, feed: HomeFeed, nowMs = Date.now()): string | null {
  if (!status?.available || !status.serviceEnabled || !status.protectionEnabled) return null;
  if (feed === 'instagram' && status.instagramObservationMode) return null;
  if (feed === 'x' && (status.xObservationMode || !status.xHomeEnabled)) return null;

  const allowanceMinutes = feed === 'instagram' ? status.instagramHomeMinutes : status.xHomeMinutes;
  const usedMs = feed === 'instagram' ? status.instagramHomeUsedMs : status.xHomeUsedMs;
  const availableAt = feed === 'instagram' ? status.instagramHomeAvailableAt : status.xHomeAvailableAt;

  if (availableAt > nowMs) return `Available again in ${formatDuration(availableAt - nowMs)}`;

  // An expired lockout refills the next Home visit even before the service sees that visit.
  const remainingMs = availableAt > 0
    ? allowanceMinutes * 60_000
    : Math.max(0, allowanceMinutes * 60_000 - usedMs);
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
