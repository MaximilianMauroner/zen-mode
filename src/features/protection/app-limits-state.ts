import type { ZenGuardStatus } from '../../../modules/zen-guard/src/ZenGuardModule';

export type AppLimitsPlatformState = 'native' | 'android-required' | 'unknown';

/** The route must decide this before touching any Android-only rule method. */
export function getAppLimitsPlatformState(status: Pick<ZenGuardStatus, 'available'> | null): AppLimitsPlatformState {
  if (!status) return 'unknown';
  return status.available ? 'native' : 'android-required';
}
