import { DangerButton, PrimaryButton, SecondaryButton } from '@/components/ui/button';
import { Card, RowGroup, SectionLabel } from '@/components/ui/card';
import { ChipGroup, ChoiceCard } from '@/components/ui/choice';
import { GuardNotes } from '@/components/ui/guard-notes';
import { Screen, ScreenHeader } from '@/components/ui/screen';
import { AppPicker } from '@/components/ui/app-picker';
import { useWeakeningGate, WeakeningGateDialog } from '@/components/ui/weakening-gate';
import { TopTabs } from 'expo-router/js-top-tabs';
import { StatusPill } from '@/components/ui/pill';
import { authorizeWeakening } from '@/features/protection/authorize-weakening';
import { appRuleLockRefusal, type AppRule } from '@/features/protection/lock-policy';
import {
  getAppLimits,
  getEarnRules,
  getInstalledApps,
  getIntentApps,
  getRollingLimits,
  removeAppLimit,
  removeEarnRule,
  removeIntentApp,
  removeRollingLimit,
  setAppLimit,
  setEarnRule,
  setIntentApp,
  setRollingLimit,
  getZenGuardStatus,
  type AppLimit,
  type EarnRule,
  type InstalledApp,
  type IntentApp,
  type RollingLimit,
} from '@/features/protection/native';
import { router, useNavigation } from 'expo-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Platform, Pressable, Text, View } from 'react-native';
import { ChevronRight } from 'lucide-react-native';
import { colors } from '@/theme/colors';
import { getAppRuleChangeAction, getConfiguredAppPresentation } from '@/features/protection/app-rule-presentation';
import { getAppLimitsPlatformState } from '@/features/protection/app-limits-state';
import { actionFailure, IDLE_ACTION, readValue, RUNNING_ACTION, type ActionState, type ReadState } from '@/features/protection/control-state';
import type { ReadResult } from '@/features/protection/protection-reads';
import { useScreenRead } from '@/features/protection/use-protection-read';

const MINUTE_OPTIONS = [5, 15, 30, 45, 60, 90, 120];
/** Fixed visit lengths for the intent question. */
const SESSION_OPTIONS = [1, 2, 5, 10];
/** Downtime between intent visits. Zero asks again on every open. */
const COOLDOWN_OPTIONS = [0, 5, 15, 30, 60];
const ALLOWANCE_OPTIONS = [1, 2, 5, 10, 15, 30, 60];
const WINDOW_OPTIONS = [30, 60, 180, 480, 1440];
/** One-tap rolling rules, e.g. 5 minutes in any hour. */
const ROLLING_PREFILLS = [
  { label: '5m / hour', allowance: 5, window: 60 },
  { label: '10m / hour', allowance: 10, window: 60 },
  { label: '5m / 3h', allowance: 5, window: 180 },
  { label: '30m / day', allowance: 30, window: 1440 },
];
/** Habit minutes that earn one exchange, and the minutes each exchange earns. */
const HABIT_OPTIONS = [10, 15, 30, 45, 60, 90];
const REWARD_OPTIONS = [1, 2, 5, 10, 15, 30];
type RuleMode = 'daily' | 'visit' | 'rolling' | 'earn';

type AppRules = {
  limits: AppLimit[];
  intents: IntentApp[];
  rolling: RollingLimit[];
  earn: EarnRule[];
  installedApps: InstalledApp[];
};

type GuardedEntry = {
  packageName: string;
  label: string;
  limit?: AppLimit;
  intent?: IntentApp;
  rolling?: RollingLimit;
  earn?: EarnRule;
  availability: 'installed' | 'absent' | 'unknown';
  availabilityDetail: string | null;
};

const MODE_LABEL: Record<RuleMode, string> = { daily: 'Daily', visit: 'Visit', rolling: 'Rolling', earn: 'Earn' };
const WINDOW_LABEL: Record<number, string> = { 30: '30 min', 60: 'hour', 180: '3 hours', 480: '8 hours', 1440: 'day' };

/**
 * Every rule stored for one app right now. The lock compares against this
 * rather than the rendered list, which can be a refresh behind.
 */
async function readStoredRules(packageName: string): Promise<AppRule[]> {
  const [limits, intents, rollingRules, earnRules] = await Promise.all([getAppLimits(), getIntentApps(), getRollingLimits(), getEarnRules()]);
  const stored: AppRule[] = [];
  for (const limit of limits) {
    if (limit.packageName === packageName) stored.push({ mode: 'daily', minutes: limit.minutes });
  }
  for (const intent of intents) {
    if (intent.packageName === packageName) {
      stored.push({ mode: 'visit', sessionMinutes: intent.sessionMinutes, cooldownMinutes: intent.cooldownMinutes });
    }
  }
  for (const rule of rollingRules) {
    if (rule.packageName === packageName) {
      stored.push({ mode: 'rolling', allowanceMinutes: rule.allowanceMinutes, windowMinutes: rule.windowMinutes });
    }
  }
  for (const rule of earnRules) {
    if (rule.packageName === packageName) {
      stored.push({ mode: 'earn', habitPackage: rule.habitPackage, habitLabel: rule.habitLabel, habitMinutes: rule.habitMinutes, rewardMinutes: rule.rewardMinutes });
    }
  }
  return stored;
}

async function readAppRules(): Promise<ReadResult<AppRules>> {
  if (Platform.OS !== 'android') return { kind: 'android-required' };
  const nativeStatus = await getZenGuardStatus();
  if (getAppLimitsPlatformState(nativeStatus) === 'android-required') return { kind: 'android-required' };
  const [limits, intents, rolling, earn] = await Promise.all([getAppLimits(), getIntentApps(), getRollingLimits(), getEarnRules()]);
  const installedApps = await getInstalledApps();
  return { kind: 'ready', value: { limits, intents, rolling, earn, installedApps } };
}

export default function AppLimitsScreen() {
  const { read, refresh } = useScreenRead(readAppRules, 'The app rules could not be loaded.');
  const rules = readValue(read);
  const apps: ReadState<readonly InstalledApp[]> = read.kind === 'ready' ? { ...read, value: read.value.installedApps } : read;
  const navigation = useNavigation();
  const [pickerOpen, setPickerOpen] = useState(false);
  const [selected, setSelected] = useState<InstalledApp | null>(null);
  const [mode, setMode] = useState<RuleMode>('daily');
  const [minutes, setMinutes] = useState(15);
  const [session, setSession] = useState(5);
  const [cooldown, setCooldown] = useState(5);
  const [allowance, setAllowance] = useState(5);
  const [window, setWindow] = useState(60);
  const [habit, setHabit] = useState<InstalledApp | null>(null);
  const [habitMinutes, setHabitMinutes] = useState(30);
  const [reward, setReward] = useState(5);
  const [habitPickerOpen, setHabitPickerOpen] = useState(false);
  const [action, setAction] = useState<ActionState>(IDLE_ACTION);
  const busy = action.kind === 'running';
  const busyRef = useRef(false);
  const gate = useWeakeningGate();

  useEffect(() => {
    const frame = requestAnimationFrame(() => { if (!navigation.isFocused()) void refresh(); });
    return () => cancelAnimationFrame(frame);
  }, [navigation, refresh]);

  const runAction = useCallback((task: () => Promise<void>, fallbackMessage: string) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setAction(RUNNING_ACTION);
    void (async () => {
      try {
        await task();
        setAction(IDLE_ACTION);
      } catch (cause) {
        setAction(actionFailure(cause, fallbackMessage));
      } finally {
        busyRef.current = false;
      }
    })();
  }, []);

  const controlsDisabled = busy || read.kind !== 'ready' || read.refreshing;

  const guardedByPackage = new Map<string, GuardedEntry>();
  const addGuarded = (packageName: string, storedLabel: string, rule: Pick<GuardedEntry, 'limit' | 'intent' | 'rolling' | 'earn'>) => {
    const existing = guardedByPackage.get(packageName);
    const evidence = getConfiguredAppPresentation(storedLabel, packageName, rules?.installedApps ?? null);
    guardedByPackage.set(packageName, {
      ...(existing ?? { packageName, label: storedLabel, availability: evidence.availability, availabilityDetail: evidence.detail }),
      label: evidence.availability === 'installed' ? evidence.label : evidence.availability === 'absent' ? evidence.label : existing?.label ?? storedLabel,
      availability: evidence.availability,
      availabilityDetail: evidence.detail,
      ...rule,
    });
  };
  rules?.limits.forEach((limit) =>
    addGuarded(limit.packageName, limit.label, { limit }),
  );
  rules?.intents.forEach((intent) =>
    addGuarded(intent.packageName, intent.label, { intent }),
  );
  rules?.rolling.forEach((rule) =>
    addGuarded(rule.packageName, rule.label, { rolling: rule }),
  );
  rules?.earn.forEach((rule) =>
    addGuarded(rule.packageName, rule.label, { earn: rule }),
  );
  const guarded = [...guardedByPackage.values()].sort((a, b) => a.label.toLowerCase().localeCompare(b.label.toLowerCase()));

  // An error or lock refusal belongs to one edit. It must not follow the user
  // into the list or into the next app's editor.
  const closeEditor = () => {
    setAction(IDLE_ACTION);
    setSelected(null);
  };

  // Opening an app pre-fills its current rule, or plain defaults for a new one.
  const selectApp = (app: InstalledApp) => {
    if (controlsDisabled) return false;
    const entry = guardedByPackage.get(app.packageName);
    if (entry?.limit) {
      setMode('daily');
      setMinutes(entry.limit.minutes);
    } else if (entry?.intent) {
      setMode('visit');
      setSession(entry.intent.sessionMinutes);
      setCooldown(entry.intent.cooldownMinutes);
    } else if (entry?.rolling) {
      setMode('rolling');
      setAllowance(entry.rolling.allowanceMinutes);
      setWindow(entry.rolling.windowMinutes);
    } else if (entry?.earn) {
      setMode('earn');
      setHabit({ packageName: entry.earn.habitPackage, label: entry.earn.habitLabel });
      setHabitMinutes(entry.earn.habitMinutes);
      setReward(entry.earn.rewardMinutes);
    } else {
      setMode('daily');
      setMinutes(15);
      setSession(5);
      setCooldown(5);
      setAllowance(5);
      setWindow(60);
      setHabit(null);
      setHabitMinutes(30);
      setReward(5);
    }
    setAction(IDLE_ACTION);
    setSelected(app);
    return true;
  };

  const proposedRule = (): AppRule | null => {
    switch (mode) {
      case 'daily':
        return { mode: 'daily', minutes };
      case 'visit':
        return { mode: 'visit', sessionMinutes: session, cooldownMinutes: cooldown };
      case 'rolling':
        return { mode: 'rolling', allowanceMinutes: allowance, windowMinutes: window };
      case 'earn':
        return habit === null ? null : { mode: 'earn', habitPackage: habit.packageName, habitLabel: habit.label, habitMinutes, rewardMinutes: reward };
    }
  };

  // One rule per app keeps enforcement predictable, so saving replaces the rest.
  const saveRule = () => {
    if (!selected || controlsDisabled) return;
    if (mode === 'rolling' && allowance > window) {
      setAction({ kind: 'failed', message: 'The allowance must fit inside its window.' });
      return;
    }
    const proposed = proposedRule();
    if (proposed === null) {
      setAction({ kind: 'failed', message: 'Choose the app that earns the time.' });
      return;
    }
    const packageName = selected.packageName;

    runAction(async () => {
      // Read what is stored right now. The cached list can be a refresh behind,
      // and the lock must judge the change that is actually being made.
      const stored = await readStoredRules(packageName);
      const refusal = appRuleLockRefusal(stored, proposed);
      const action = getAppRuleChangeAction(selected.label, stored, proposed);
      if (!await authorizeWeakening(refusal !== null, action, gate.request)) return;
      if (JSON.stringify(await readStoredRules(packageName)) !== JSON.stringify(stored)) {
        throw new Error('The app rule changed. Review it and try again.');
      }
      if (proposed.mode === 'daily') await setAppLimit(packageName, proposed.minutes);
      else await removeAppLimit(packageName);
      if (proposed.mode === 'visit') await setIntentApp(packageName, proposed.sessionMinutes, proposed.cooldownMinutes);
      else await removeIntentApp(packageName);
      if (proposed.mode === 'rolling') await setRollingLimit(packageName, proposed.allowanceMinutes, proposed.windowMinutes);
      else await removeRollingLimit(packageName);
      if (proposed.mode === 'earn') await setEarnRule(packageName, proposed.habitPackage, proposed.habitMinutes, proposed.rewardMinutes);
      else await removeEarnRule(packageName);
      setSelected(null);
      await refresh();
    }, 'That rule could not be saved.');
  };

  // Removing rules loosens the guard, so the lock has a say.
  const dropRules = (packageName: string) =>
    runAction(async () => {
      const stored = await readStoredRules(packageName);
      if (stored.length === 0) return;
      const label = guardedByPackage.get(packageName)?.label ?? packageName;
      if (!await authorizeWeakening(true, `remove all limits for ${label}`, gate.request)) return;
      if (JSON.stringify(await readStoredRules(packageName)) !== JSON.stringify(stored)) {
        throw new Error('The app rules changed. Review them and try again.');
      }
      await removeAppLimit(packageName);
      await removeIntentApp(packageName);
      await removeRollingLimit(packageName);
      await removeEarnRule(packageName);
      if (selected?.packageName === packageName) setSelected(null);
      await refresh();
    }, 'Those rules could not be removed.');


  return (
    <Screen edges={[]}>
      <WeakeningGateDialog gate={gate} />
      <TopTabs.Screen options={{ swipeEnabled: !busy }} />
      {selected ? <ScreenHeader title={selected.label} onBack={closeEditor} backDisabled={busy} /> : null}

      {selected ? (
        <>

          <View>
            <SectionLabel className="mb-2">Rule</SectionLabel>
            <Card>
              <ModeRow selected={mode} disabled={controlsDisabled} onSelect={setMode} />
            </Card>
          </View>

          {mode === 'daily' ? (
            <View>
              <SectionLabel className="mb-2">Daily budget</SectionLabel>
              <Card>
                <Text className="mb-3 text-support text-muted">When it runs out, a blocking card shows the countdown until access returns at local midnight.</Text>
                <MinuteRow selected={minutes} disabled={controlsDisabled} onSelect={setMinutes} />
              </Card>
            </View>
          ) : mode === 'visit' ? (
            <View>
              <SectionLabel className="mb-2">Timed visit</SectionLabel>
              <Card>
                <Text className="mb-3 text-support text-muted">
                  Opening the app asks first. Confirming starts one visit of this length, then the app rests.
                </Text>
                <SectionLabel className="mb-2">Visit length</SectionLabel>
                <MinuteRow options={SESSION_OPTIONS} selected={session} disabled={controlsDisabled} onSelect={setSession} />
                <SectionLabel className="mb-2 mt-4">Downtime</SectionLabel>
                <CooldownRow selected={cooldown} disabled={controlsDisabled} onSelect={setCooldown} />
              </Card>
            </View>
          ) : mode === 'earn' ? (
            <View>
              <SectionLabel className="mb-2">Earn it first</SectionLabel>
              <Card>
                <Text className="mb-3 text-support text-muted">
                  {selected.label} stays closed until you earn time in another app, like a reading or language app. Every minute there counts, and the card shows a button to open it.
                </Text>
                <SectionLabel className="mb-2">Earn time in</SectionLabel>
                <SecondaryButton
                  title={habit ? `${habit.label} · Change` : 'Choose app'}
                  disabled={controlsDisabled}
                  onPress={() => setHabitPickerOpen(true)}
                />
                <SectionLabel className="mb-2 mt-4">Every</SectionLabel>
                <MinuteRow options={HABIT_OPTIONS} selected={habitMinutes} disabled={controlsDisabled} onSelect={setHabitMinutes} />
                <SectionLabel className="mb-2 mt-4">Earns</SectionLabel>
                <MinuteRow options={REWARD_OPTIONS} selected={reward} disabled={controlsDisabled} onSelect={setReward} />
              </Card>
            </View>
          ) : (
            <View>
              <SectionLabel className="mb-2">Rolling allowance</SectionLabel>
              <Card>
                <Text className="mb-3 text-support text-muted">
                  Use counts inside the rolling window. At the limit, a blocking card shows when access returns as old use leaves the window.
                </Text>
                <View className="mb-4">
                  <ChipGroup
                    label="Common allowances"
                    options={ROLLING_PREFILLS.map((prefill) => ({ value: prefill.label, label: prefill.label }))}
                    selected={ROLLING_PREFILLS.find((prefill) => prefill.allowance === allowance && prefill.window === window)?.label}
                    disabled={controlsDisabled}
                    onSelect={(label) => {
                      const prefill = ROLLING_PREFILLS.find((option) => option.label === label);
                      if (!prefill) return;
                      setAllowance(prefill.allowance);
                      setWindow(prefill.window);
                    }}
                  />
                </View>
                <SectionLabel className="mb-2">Allowance</SectionLabel>
                <MinuteRow options={ALLOWANCE_OPTIONS} selected={allowance} disabled={controlsDisabled} onSelect={setAllowance} />
                <SectionLabel className="mb-2 mt-4">Every</SectionLabel>
                <WindowRow selected={window} disabled={controlsDisabled} onSelect={setWindow} />
              </Card>
            </View>
          )}

          <PrimaryButton title={busy ? 'Working…' : 'Save rule'} disabled={controlsDisabled} onPress={saveRule} />
          {guardedByPackage.has(selected.packageName) ? (
            <DangerButton title={busy ? 'Working…' : 'Remove rules'} disabled={controlsDisabled} onPress={() => dropRules(selected.packageName)} />
          ) : null}
          <SecondaryButton title="Cancel" disabled={busy} onPress={closeEditor} />
        </>
      ) : (
        <>

          <View>
            {read.kind === 'android-required' ? (
              <Card>
                <Text className="text-support text-muted">App limits require the native Android app. This web preview cannot read or enforce app rules.</Text>
              </Card>
            ) : read.kind === 'failed' ? null : rules === null ? (
              <Card>
                <Text className="text-support text-muted">Reading your app rules.</Text>
              </Card>
            ) : guarded.length === 0 ? (
              <Card>
                <Text className="text-item font-semibold text-copy">No app limits yet.</Text>
                <Text className="mt-1 text-support text-muted">Add an app to give it a daily budget, a timed visit, a rolling allowance, or time you earn in another app.</Text>
              </Card>
            ) : (
              <RowGroup>
                {guarded.map((entry) => (
                  <GuardedRow key={entry.packageName} entry={entry} disabled={controlsDisabled} onEdit={() => selectApp({ packageName: entry.packageName, label: entry.label })} />
                ))}
              </RowGroup>
            )}
          </View>

          <SecondaryButton title="＋ Add app" disabled={controlsDisabled} onPress={() => setPickerOpen(true)} />
        </>
      )}

      <AppPicker visible={pickerOpen} disabled={controlsDisabled} apps={apps} onRetry={() => void refresh()} configuredPackages={new Set(guardedByPackage.keys())} onClose={() => setPickerOpen(false)} onSelect={selectApp} />
      <AppPicker
        visible={habitPickerOpen}
        disabled={controlsDisabled}
        apps={apps}
        onRetry={() => void refresh()}
        configuredPackages={new Set(selected ? [selected.packageName] : [])}
        onClose={() => setHabitPickerOpen(false)}
        onSelect={(app) => { setHabit(app); return true; }}
        title="Earn time in"
        itemAction="Choose"
      />
      <GuardNotes reads={[read]} action={action} />
      {action.kind === 'refused' ? <SecondaryButton title="Manage lock" disabled={busy} onPress={() => router.navigate('/lock')} /> : null}
      {read.kind === 'failed' ? <SecondaryButton title="Try again" disabled={busy} onPress={() => void refresh()} /> : null}
    </Screen>
  );
}

/** One guarded app with its rule summary. Editing lives behind the row. */
function GuardedRow({ entry, disabled, onEdit }: { entry: GuardedEntry; disabled: boolean; onEdit: () => void }) {
  const summaries: string[] = [];
  let pill = 'Rule';
  let spentTone: 'accent' | 'danger' = 'accent';
  if (entry.limit) {
    const used = Math.floor(entry.limit.usedMs / 60_000);
    summaries.push(`${used} of ${entry.limit.minutes} minutes today`);
    pill = MODE_LABEL.daily;
    if (entry.limit.usedMs >= entry.limit.minutes * 60_000) {
      pill = 'Spent';
      spentTone = 'danger';
    }
  }
  if (entry.intent) {
    summaries.push(
      `${entry.intent.sessionMinutes}m visits${entry.intent.cooldownMinutes === 0 ? ' · asks every time' : ` · ${entry.intent.cooldownMinutes}m rest`}`,
    );
    pill = MODE_LABEL.visit;
  }
  if (entry.rolling) {
    const used = Math.floor(entry.rolling.usedMs / 60_000);
    summaries.push(`${used} of ${entry.rolling.allowanceMinutes}m per ${WINDOW_LABEL[entry.rolling.windowMinutes] ?? `${entry.rolling.windowMinutes}m`}`);
    pill = MODE_LABEL.rolling;
    if (entry.rolling.usedMs >= entry.rolling.allowanceMinutes * 60_000) {
      pill = 'Spent';
      spentTone = 'danger';
    }
  }
  if (entry.earn) {
    const banked = Math.floor(entry.earn.balanceMs / 60_000);
    summaries.push(`${entry.earn.habitMinutes}m in ${entry.earn.habitLabel} earns ${entry.earn.rewardMinutes}m · ${banked}m left`);
    pill = MODE_LABEL.earn;
    if (entry.earn.balanceMs <= 0) {
      pill = 'Closed';
      spentTone = 'danger';
    }
  }

  return (
    <Pressable accessibilityRole="button" accessibilityLabel={`Edit ${entry.label}`} accessibilityState={{ disabled }} disabled={disabled} onPress={onEdit} className={`min-h-14 flex-row items-center gap-3 py-3 ${disabled ? 'opacity-40' : 'active:opacity-60'}`}>
      <View className="flex-1">
        <Text className="text-item font-semibold text-copy">{entry.label}</Text>
        {entry.availability !== 'installed' && entry.availabilityDetail ? <Text className="mt-0.5 text-support text-muted">{entry.availabilityDetail}</Text> : null}
        {summaries.map((summary) => <Text key={summary} className="mt-0.5 text-support text-muted">{summary}</Text>)}
      </View>
      <StatusPill label={pill} tone={spentTone} />
      <ChevronRight color={colors.muted} size={18} />
    </Pressable>
  );
}

function OptionRow({ options, selected, disabled, onSelect, format = (value) => `${value}m` }: { options: number[]; selected: number; disabled: boolean; onSelect: (value: number) => void; format?: (value: number) => string }) {
  return <ChipGroup options={options.map((value) => ({ value, label: format(value) }))} selected={selected} disabled={disabled} onSelect={onSelect} />;
}

function MinuteRow({ options = MINUTE_OPTIONS, selected, disabled, onSelect }: { options?: number[]; selected: number; disabled: boolean; onSelect: (value: number) => void }) {
  return <OptionRow options={options} selected={selected} disabled={disabled} onSelect={onSelect} />;
}

function CooldownRow({ selected, disabled, onSelect }: { selected: number; disabled: boolean; onSelect: (value: number) => void }) {
  return <OptionRow options={COOLDOWN_OPTIONS} selected={selected} disabled={disabled} onSelect={onSelect} format={(value) => (value === 0 ? 'Every time' : `${value}m`)} />;
}

function WindowRow({ selected, disabled, onSelect }: { selected: number; disabled: boolean; onSelect: (value: number) => void }) {
  return <OptionRow options={WINDOW_OPTIONS} selected={selected} disabled={disabled} onSelect={onSelect} format={(value) => WINDOW_LABEL[value] ?? `${value}m`} />;
}

function ModeRow({ selected, disabled, onSelect }: { selected: RuleMode; disabled: boolean; onSelect: (mode: RuleMode) => void }) {
  const modes: { mode: RuleMode; title: string; detail: string }[] = [
    { mode: 'daily', title: 'Daily', detail: 'Budget per day' },
    { mode: 'visit', title: 'Visit', detail: 'Ask, then time it' },
    { mode: 'rolling', title: 'Rolling', detail: 'Refills over time' },
    { mode: 'earn', title: 'Earn', detail: 'Use another app first' },
  ];
  return (
    <View accessibilityRole="radiogroup" accessibilityLabel="Rule type" className="gap-2">
      {modes.map(({ mode, title, detail }) => (
        <ChoiceCard key={mode} title={title} detail={detail} selected={selected === mode} disabled={disabled} onPress={() => onSelect(mode)} />
      ))}
    </View>
  );
}
