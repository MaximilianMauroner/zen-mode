import * as SecureStore from 'expo-secure-store';
import { Platform } from 'react-native';
import { armLock, readLockState, updatePasscode } from './lock';
import { acceptCurrentNativeConsent, getZenGuardStatus, hasCurrentNativeConsent, setNativeProtectionEnabled } from './native';
import { applyRecommendedDefaults, type GuidedSetupMode } from './recommended-defaults';
import { canFinishAndroidSetup, CONSENT_VERSION, isSetupComplete } from './setup-policy';

const SETUP_COMPLETE_KEY = `zen-mode.setup-complete.v${CONSENT_VERSION}`;

export type SetupLock =
  | { kind: 'passcode'; passcode: string; confirmation: string }
  | { kind: 'time'; days: number };

export type SetupPlan =
  | { mode: GuidedSetupMode; lock: SetupLock }
  | { mode: 'recommended' | 'customize' | 'restore' };

/**
 * A missing or outdated consent record disables protection before setup can
 * continue. This makes an updated disclosure require a fresh agreement.
 */
export async function hasCompletedSetup(): Promise<boolean> {
  const secureStoreComplete = (await SecureStore.getItemAsync(SETUP_COMPLETE_KEY)) === 'true';
  const nativeConsent = Platform.OS === 'android' ? await hasCurrentNativeConsent() : true;
  const complete = isSetupComplete(Platform.OS, secureStoreComplete, nativeConsent);
  if (!complete) await setNativeProtectionEnabled(false);
  return complete;
}

export async function acceptSetupConsent(): Promise<void> {
  if (Platform.OS === 'android') await acceptCurrentNativeConsent();
}

async function confirmAndroidAccess() {
  const status = await getZenGuardStatus();
  if (!canFinishAndroidSetup(status, await hasCurrentNativeConsent())) {
    throw new Error('Enable Zen Mode in Android Accessibility settings, then return and try again.');
  }
  return status;
}

export async function finishAndroidSetup(plan: SetupPlan): Promise<'home' | 'customize' | 'family-link'> {
  const status = await confirmAndroidAccess();
  const preserveRules = plan.mode === 'restore' || (await readLockState()).kind !== 'open';
  const customize = !preserveRules && plan.mode === 'customize';
  if (!preserveRules) {
    if (plan.mode === 'recommended') await applyRecommendedDefaults();
    if (plan.mode === 'kids' || plan.mode === 'focus') {
      await applyRecommendedDefaults({ mode: plan.mode, status });
      if (plan.lock.kind === 'passcode') await updatePasscode(plan.lock);
      else await armLock(plan.lock.days);
    }
  }
  if (!customize) await setNativeProtectionEnabled(true);
  await confirmAndroidAccess();
  await SecureStore.setItemAsync(SETUP_COMPLETE_KEY, 'true');
  if (customize) return 'customize';
  const kids = preserveRules ? status.endScreen === 'kids' : plan.mode === 'kids';
  return kids ? 'family-link' : 'home';
}
