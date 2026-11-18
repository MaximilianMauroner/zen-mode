import * as SecureStore from 'expo-secure-store';
import { Platform } from 'react-native';
import { armLock, readLockState, updatePasscode } from './lock';
import { acceptCurrentNativeConsent, getZenGuardStatus, hasCurrentNativeConsent, removePendingGuidedCaps, setNativeProtectionEnabled } from './native';
import { applyGuidedDefaults, applyRecommendedDefaults, type GuidedSetupMode } from './recommended-defaults';
import { canFinishAndroidSetup, CONSENT_VERSION, isSetupComplete } from './setup-policy';

const SETUP_COMPLETE_KEY = `zen-mode.setup-complete.v${CONSENT_VERSION}`;
const FAMILY_LINK_PENDING = 'family-link';

type SetupProgress = 'incomplete' | 'complete' | typeof FAMILY_LINK_PENDING;

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
export async function readSetupProgress(): Promise<SetupProgress> {
  const android = Platform.OS === 'android';
  const record = Platform.OS === 'web'
    ? localStorage.getItem(SETUP_COMPLETE_KEY)
    : await SecureStore.getItemAsync(SETUP_COMPLETE_KEY);
  const nativeConsent = android ? await hasCurrentNativeConsent() : true;
  if (isSetupComplete(Platform.OS, record === 'true', nativeConsent)) return 'complete';
  if (android && nativeConsent && record === FAMILY_LINK_PENDING) return FAMILY_LINK_PENDING;
  if (android) await setNativeProtectionEnabled(false);
  return 'incomplete';
}

export async function hasCompletedSetup(): Promise<boolean> {
  return (await readSetupProgress()) === 'complete';
}

export async function markSetupComplete(): Promise<void> {
  if (Platform.OS === 'web') localStorage.setItem(SETUP_COMPLETE_KEY, 'true');
  else await SecureStore.setItemAsync(SETUP_COMPLETE_KEY, 'true');
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
    if (plan.mode !== 'kids') await removePendingGuidedCaps();
    if (plan.mode === 'recommended') await applyRecommendedDefaults();
    if (plan.mode === 'kids' || plan.mode === 'focus') {
      await applyGuidedDefaults(plan.mode, status);
      if (plan.lock.kind === 'passcode') await updatePasscode(plan.lock);
      else await armLock(plan.lock.days);
    }
  }
  await confirmAndroidAccess();
  if (!customize) await setNativeProtectionEnabled(true);
  const kids = preserveRules ? status.endScreen === 'kids' : plan.mode === 'kids';
  if (kids) {
    await SecureStore.setItemAsync(SETUP_COMPLETE_KEY, FAMILY_LINK_PENDING);
    return 'family-link';
  }
  await markSetupComplete();
  return customize ? 'customize' : 'home';
}
