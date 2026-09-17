import type { InstalledApp } from '../../../modules/zen-guard/src/ZenGuardModule';

export type ConfiguredAppAvailability = 'installed' | 'absent' | 'unknown';

export type ConfiguredAppPresentation = {
  label: string;
  availability: ConfiguredAppAvailability;
  detail: string | null;
};

/**
 * App-rule rows use the fresh launchable-app inventory only to refresh labels.
 * A missing picker row is not proof that an arbitrary package was uninstalled,
 * so the saved rule stays labelled as currently unavailable rather than being
 * called absent.
 */
export function getConfiguredAppPresentation(
  storedLabel: string,
  packageName: string,
  installedApps: readonly InstalledApp[] | null,
): ConfiguredAppPresentation {
  if (installedApps === null) return { label: storedLabel, availability: 'unknown', detail: null };
  const installed = installedApps.find((app) => app.packageName === packageName);
  if (installed) return { label: installed.label, availability: 'installed', detail: null };
  return {
    label: storedLabel,
    availability: 'unknown',
    detail: `${storedLabel} is not currently available in the app picker. The saved rule stays here and can apply if the app returns.`,
  };
}
