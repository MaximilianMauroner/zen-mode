import type { ZenGuardStatus } from '../../../modules/zen-guard/src/ZenGuardModule';

export const DEFAULT_TIMED_VISIT = { sessionMinutes: 5, cooldownMinutes: 5 } as const;

export type AppLimitsPlatformState = 'native' | 'android-required' | 'unknown';

/** The route must decide this before touching any Android-only rule method. */
export function getAppLimitsPlatformState(status: Pick<ZenGuardStatus, 'available'> | null): AppLimitsPlatformState {
  if (!status) return 'unknown';
  return status.available ? 'native' : 'android-required';
}
