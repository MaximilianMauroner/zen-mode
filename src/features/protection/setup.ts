import * as SecureStore from 'expo-secure-store';
import { Platform } from 'react-native';
import { acceptCurrentNativeConsent, getZenGuardStatus, hasCurrentNativeConsent, setNativeProtectionEnabled } from './native';
import { canFinishAndroidSetup, CONSENT_VERSION, isSetupComplete } from './setup-policy';

const SETUP_COMPLETE_KEY = `zen-mode.setup-complete.v${CONSENT_VERSION}`;

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

export async function confirmAndroidAccess(): Promise<void> {
  if (Platform.OS === 'android') {
    const status = await getZenGuardStatus();
    if (!canFinishAndroidSetup(status, await hasCurrentNativeConsent())) {
      throw new Error('Enable Zen Mode in Android Accessibility settings, then return and try again.');
    }
  }
}

export async function markSetupComplete(): Promise<void> {
  await confirmAndroidAccess();
  await SecureStore.setItemAsync(SETUP_COMPLETE_KEY, 'true');
}
