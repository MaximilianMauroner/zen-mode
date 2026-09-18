import type { PackageAvailability } from './target-availability.ts';

export type FeedDrawerTargetPresentation = {
  showObservationGuidance: boolean;
  showPostSetupGuidance: boolean;
  showOpener: boolean;
  recovery: string | null;
};

/** Keep impossible observation actions out of a feed drawer without hiding recovery. */
export function getFeedDrawerTargetPresentation(
  availability: PackageAvailability,
  appLabel: string,
  observing: boolean,
  enabled: boolean,
  hasPostSetupGuidance: boolean,
): FeedDrawerTargetPresentation {
  const installed = availability === 'installed';
  const showOpener = installed || availability === 'unknown';
  const recovery = availability === 'absent'
    ? `${appLabel} is not installed. Install it to run this check. Saved rules stay unchanged.`
    : availability === 'disabled'
      ? `${appLabel} is disabled in Android. Enable it to run this check. Saved rules stay unchanged.`
      : availability === 'unknown'
        ? `${appLabel} availability could not be confirmed. Refresh after checking Android access. Saved rules stay unchanged.`
        : availability === 'unavailable'
          ? `${appLabel} is unavailable in this native build. Use the Android app to run this check.`
          : null;
  return {
    showObservationGuidance: installed && observing && enabled,
    showPostSetupGuidance: installed && hasPostSetupGuidance,
    showOpener,
    recovery,
  };
}
