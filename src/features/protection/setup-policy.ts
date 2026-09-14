export const CONSENT_VERSION = 2;

export function isSetupComplete(platform: string, secureStoreComplete: boolean, nativeConsent: boolean): boolean {
  return secureStoreComplete && (platform !== 'android' || nativeConsent);
}
