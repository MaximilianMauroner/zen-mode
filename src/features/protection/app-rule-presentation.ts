import type { InstalledApp } from '../../../modules/zen-guard/src/ZenGuardModule';
import type { AppRule } from './lock-policy';

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

function describeRule(rule: AppRule): string {
  switch (rule.mode) {
    case 'daily':
      return `a daily limit of ${rule.minutes} minutes`;
    case 'visit':
      return `a timed visit of ${rule.sessionMinutes} minutes with a ${rule.cooldownMinutes}-minute cooldown`;
    case 'rolling':
      return `a rolling allowance of ${rule.allowanceMinutes} minutes per ${rule.windowMinutes}-minute window`;
  }
}

/** The final consent names every parameter and every rule the save replaces. */
export function getAppRuleChangeAction(label: string, stored: readonly AppRule[], proposed: AppRule): string {
  const nextRule = describeRule(proposed);
  if (stored.length === 0) return `set ${label}'s rule to ${nextRule}`;
  return `replace ${label}'s existing rules (${stored.map(describeRule).join('; ')}) with ${nextRule}`;
}
