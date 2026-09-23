export const CONSENT_VERSION = 3;

export function isSetupComplete(platform: string, secureStoreComplete: boolean, nativeConsent: boolean): boolean {
  return secureStoreComplete && (platform !== 'android' || nativeConsent);
}

export function canFinishAndroidSetup(status: { available: boolean; serviceEnabled: boolean; currentConsent: boolean }, nativeConsent: boolean): boolean {
  return status.available && status.serviceEnabled && status.currentConsent && nativeConsent;
}
