import * as SecureStore from 'expo-secure-store';

const SETUP_COMPLETE_KEY = 'zen-mode.setup-complete.v1';

export async function hasCompletedSetup(): Promise<boolean> {
  return (await SecureStore.getItemAsync(SETUP_COMPLETE_KEY)) === 'true';
}

export async function markSetupComplete(): Promise<void> {
  await SecureStore.setItemAsync(SETUP_COMPLETE_KEY, 'true');
}
