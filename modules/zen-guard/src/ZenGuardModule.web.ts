import { NativeModule, registerWebModule } from 'expo';
import type { ZenGuardStatus } from './ZenGuardModule';

// ZenGuardModule is not available on the web platform.
class ZenGuardModule extends NativeModule<{}> {
  async getStatus(): Promise<ZenGuardStatus> {
    return {
      available: false,
      serviceEnabled: false,
      currentConsent: false,
      protectionEnabled: false,
      observationMode: true,
      shortsEnabled: true,
      xHomeEnabled: true,
      xVideosEnabled: true,
      xHomeMinutes: 5,
      xHomeUsedMs: 0,
      xHomeAvailableAt: 0,
      xObservationMode: true,
      xSignalMask: 0,
      lastEventAt: 0,
      lastDetectionAt: 0,
      detectionCount: 0,
      lastDetectionReason: '',
      instagramObservationMode: true,
      instagramWaitSeconds: 30,
      instagramReelsMinutes: 5,
      instagramHomeMinutes: 5,
      instagramHomeUsedMs: 0,
      instagramHomeAvailableAt: 0,
      instagramExploreBlocked: true,
      instagramLastDetectionAt: 0,
      instagramDetectionCount: 0,
      instagramSignalMask: 0,
      instagramLastDetectionReason: '',
      adultSiteEnabled: false,
      adultSiteCustomCount: 0,
      browserSignalMask: 0,
    };
  }

  async openAccessibilitySettings() {}
  async openYouTube() {}
  async openInstagram() {}
  async openX() {}
  async setShortsEnabled() {}
  async setXSettings() {}
  async setXObservationMode() {}
  async hasCurrentConsent() { return false; }
  async acceptCurrentConsent() {}
  async setProtectionEnabled() {}
  async setObservationMode() {}
  async setInstagramObservationMode() {}
  async setInstagramSettings() {}
  async getAdultSiteSettings() { return { available: false, enabled: false, customHosts: [], browserSignalMask: 0 }; }
  async setAdultSiteBlockingEnabled() {}
  async addBlockedDomain(input: string) { return input; }
  async removeBlockedDomain() {}
  async openBrowserCheck() {}
}

export default registerWebModule(ZenGuardModule, 'ZenGuard');
