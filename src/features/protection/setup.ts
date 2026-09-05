import * as SecureStore from 'expo-secure-store';
import { setNativeProtectionEnabled } from './native';

const SETUP_COMPLETE_KEY = 'zen-mode.setup-complete.v2';

/**
 * A missing or outdated consent record disables protection before setup can
 * continue. This makes an updated disclosure require a fresh agreement.
 */
export async function hasCompletedSetup(): Promise<boolean> {
  const complete = (await SecureStore.getItemAsync(SETUP_COMPLETE_KEY)) === 'true';
  if (!complete) await setNativeProtectionEnabled(false);
  return complete;
}

export async function markSetupComplete(): Promise<void> {
  await SecureStore.setItemAsync(SETUP_COMPLETE_KEY, 'true');
}
