import { DangerButton, PrimaryButton, SecondaryButton } from '@/components/ui/button';
import { Card, Row, RowGroup, SectionLabel } from '@/components/ui/card';
import { ErrorNote, Screen, ScreenHeader, ScreenTitle } from '@/components/ui/screen';
import { StatusPill } from '@/components/ui/pill';
import { isChangeBlocked } from '@/features/protection/lock';
import {
  getAppLimits,
  getInstalledApps,
  getIntentApps,
  getRollingLimits,
  removeAppLimit,
  removeIntentApp,
  removeRollingLimit,
  setAppLimit,
  setIntentApp,
  setRollingLimit,
  type AppLimit,
  type InstalledApp,
  type IntentApp,
  type RollingLimit,
} from '@/features/protection/native';
import { colors } from '@/theme/colors';
import { router, useFocusEffect } from 'expo-router';
import { useCallback, useRef, useState } from 'react';
import { Pressable, Text, TextInput, View } from 'react-native';

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
type ReadState = 'loading' | 'ready' | 'error';
type RuleMode = 'daily' | 'visit' | 'rolling';

type GuardedEntry = {
  packageName: string;
  label: string;
  limit?: AppLimit;
  intent?: IntentApp;
  rolling?: RollingLimit;
};

const MODE_LABEL: Record<RuleMode, string> = { daily: 'DAILY', visit: 'VISIT', rolling: 'ALLOW' };
const WINDOW_LABEL: Record<number, string> = { 30: '30 min', 60: 'hour', 180: '3 hours', 480: '8 hours', 1440: 'day' };

export default function AppLimitsScreen() {
  const [limits, setLimits] = useState<AppLimit[] | null>(null);
  const [intents, setIntents] = useState<IntentApp[] | null>(null);
  const [rolling, setRolling] = useState<RollingLimit[] | null>(null);
  const [apps, setApps] = useState<InstalledApp[] | null>(null);
  const [readState, setReadState] = useState<ReadState>('loading');
  const [selected, setSelected] = useState<InstalledApp | null>(null);
  const [mode, setMode] = useState<RuleMode>('daily');
  const [search, setSearch] = useState('');
  const [minutes, setMinutes] = useState(15);
  const [session, setSession] = useState(5);
  const [cooldown, setCooldown] = useState(5);
  const [allowance, setAllowance] = useState(5);
  const [window, setWindow] = useState(60);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const refreshInFlightRef = useRef(false);

  const refresh = useCallback(async () => {
    if (refreshInFlightRef.current) return;
    refreshInFlightRef.current = true;
    setReadState('loading');
    setError('');
    try {
      const [nextLimits, nextApps, nextIntents, nextRolling] = await Promise.all([
        getAppLimits(),
        getInstalledApps(),
        getIntentApps(),
        getRollingLimits(),
      ]);
      setLimits(nextLimits);
      setApps(nextApps);
      setIntents(nextIntents);
      setRolling(nextRolling);
      setReadState('ready');
    } catch {
      setLimits(null);
      setApps(null);
      setIntents(null);
      setRolling(null);
      setReadState('error');
      setError('The app rules could not be loaded.');
    } finally {
      refreshInFlightRef.current = false;
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      void refresh();
    }, [refresh]),
  );

  const runAction = useCallback((task: () => Promise<void>, fallbackMessage: string) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setError('');
    void (async () => {
      try {
        await task();
      } catch (cause) {
        setError(cause instanceof Error ? cause.message : fallbackMessage);
      } finally {
        busyRef.current = false;
        setBusy(false);
      }
    })();
  }, []);

  const currentLimits = readState === 'ready' ? limits : null;
  const currentIntents = readState === 'ready' ? intents : null;
  const currentRolling = readState === 'ready' ? rolling : null;

  const guardedByPackage = new Map<string, GuardedEntry>();
  currentLimits?.forEach((limit) =>
    guardedByPackage.set(limit.packageName, { ...(guardedByPackage.get(limit.packageName) ?? { packageName: limit.packageName, label: limit.label }), label: limit.label, limit }),
  );
  currentIntents?.forEach((intent) =>
    guardedByPackage.set(intent.packageName, { ...(guardedByPackage.get(intent.packageName) ?? { packageName: intent.packageName, label: intent.label }), label: intent.label, intent }),
  );
  currentRolling?.forEach((rule) =>
    guardedByPackage.set(rule.packageName, { ...(guardedByPackage.get(rule.packageName) ?? { packageName: rule.packageName, label: rule.label }), label: rule.label, rolling: rule }),
  );
  const guarded = [...guardedByPackage.values()].sort((a, b) => a.label.toLowerCase().localeCompare(b.label.toLowerCase()));

  const query = search.trim().toLowerCase();
  const unguarded = (apps ?? [])
    .filter((app) => !guardedByPackage.has(app.packageName))
    .filter((app) => query === '' || app.label.toLowerCase().includes(query))
    .slice(0, 40);

  // Opening an app pre-fills its current rule, or plain defaults for a new one.
  const selectApp = (app: InstalledApp) => {
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
    } else {
      setMode('daily');
      setMinutes(15);
      setSession(5);
      setCooldown(5);
      setAllowance(5);
      setWindow(60);
    }
    setSelected(app);
  };

  // One rule per app keeps enforcement predictable, so saving replaces the rest.
  const saveRule = () => {
    if (!selected) return;
    if (mode === 'rolling' && allowance > window) {
      setError('The allowance must fit inside its window.');
      return;
    }
    const packageName = selected.packageName;
    runAction(async () => {
      if (mode === 'daily') {
        await setAppLimit(packageName, minutes);
        await removeIntentApp(packageName);
        await removeRollingLimit(packageName);
      } else if (mode === 'visit') {
        await setIntentApp(packageName, session, cooldown);
        await removeAppLimit(packageName);
        await removeRollingLimit(packageName);
      } else {
        await setRollingLimit(packageName, allowance, window);
        await removeAppLimit(packageName);
        await removeIntentApp(packageName);
      }
      setSelected(null);
      await refresh();
    }, 'That rule could not be saved.');
  };

  // Removing rules loosens the guard, so the lock has a say.
  const dropRules = (packageName: string) =>
    runAction(async () => {
      if (await isChangeBlocked()) {
        setError('Removing a rule loosens the guard. Ask to unlock, then wait a day.');
        router.push('/lock');
        return;
      }
      await removeAppLimit(packageName);
      await removeIntentApp(packageName);
      await removeRollingLimit(packageName);
      if (selected?.packageName === packageName) setSelected(null);
      await refresh();
    }, 'Those rules could not be removed.');

  const goBack = () => runAction(async () => router.back(), 'Could not go back.');

  return (
    <Screen>
      <ScreenHeader
        label="APP RULES"
        onBack={selected ? () => setSelected(null) : goBack}
        backDisabled={busy}
        pill={{ label: readState !== 'ready' ? '—' : `${guarded.length}`, tone: guarded.length > 0 ? 'accent' : 'neutral' }}
      />

      {selected ? (
        <>
          <ScreenTitle title={selected.label} description="One rule per app. Saving replaces whatever this app had." />

          <View>
            <SectionLabel className="mb-2.5">RULE</SectionLabel>
            <Card>
              <ModeRow selected={mode} disabled={busy} onSelect={setMode} />
            </Card>
          </View>

          {mode === 'daily' ? (
            <View>
              <SectionLabel className="mb-2.5">DAILY BUDGET</SectionLabel>
              <Card>
                <Text className="mb-3 text-[13px] leading-[19px] text-muted">When it runs out, Zen Mode sends you home until midnight.</Text>
                <MinuteRow selected={minutes} disabled={busy} onSelect={setMinutes} />
              </Card>
            </View>
          ) : mode === 'visit' ? (
            <View>
              <SectionLabel className="mb-2.5">TIMED VISIT</SectionLabel>
              <Card>
                <Text className="mb-3 text-[13px] leading-[19px] text-muted">
                  Opening the app asks first. Confirming starts one visit of this length, then the app rests.
                </Text>
                <SectionLabel className="mb-2">VISIT LENGTH</SectionLabel>
                <MinuteRow options={SESSION_OPTIONS} selected={session} disabled={busy} onSelect={setSession} />
                <SectionLabel className="mb-2 mt-4">DOWNTIME</SectionLabel>
                <CooldownRow selected={cooldown} disabled={busy} onSelect={setCooldown} />
              </Card>
            </View>
          ) : (
            <View>
              <SectionLabel className="mb-2.5">ROLLING ALLOWANCE</SectionLabel>
              <Card>
                <Text className="mb-3 text-[13px] leading-[19px] text-muted">
                  Use counts on its own inside the window. When it runs out, the app closes until old use ages out.
                </Text>
                <View className="mb-4 flex-row flex-wrap gap-2">
                  {ROLLING_PREFILLS.map((prefill) => {
                    const active = allowance === prefill.allowance && window === prefill.window;
                    return (
                      <Pressable
                        key={prefill.label}
                        accessibilityRole="radio"
                        accessibilityState={{ selected: active, disabled: busy }}
                        className={`items-center rounded-xl border px-3 py-2 ${active ? 'border-accent bg-accent' : 'border-line bg-panel2'} ${busy ? 'opacity-40' : 'active:opacity-70'}`}
                        disabled={busy}
                        onPress={() => {
                          setAllowance(prefill.allowance);
                          setWindow(prefill.window);
                        }}>
                        <Text className={`text-[14px] font-bold ${active ? 'text-onAccent' : 'text-muted'}`}>{prefill.label}</Text>
                      </Pressable>
                    );
                  })}
                </View>
                <SectionLabel className="mb-2">ALLOWANCE</SectionLabel>
                <MinuteRow options={ALLOWANCE_OPTIONS} selected={allowance} disabled={busy} onSelect={setAllowance} />
                <SectionLabel className="mb-2 mt-4">EVERY</SectionLabel>
                <WindowRow selected={window} disabled={busy} onSelect={setWindow} />
              </Card>
            </View>
          )}

          <PrimaryButton title={busy ? 'Working…' : 'Save rule'} disabled={busy} onPress={saveRule} />
          {guardedByPackage.has(selected.packageName) ? (
            <DangerButton title={busy ? 'Working…' : 'Remove rules'} disabled={busy} onPress={() => dropRules(selected.packageName)} />
          ) : null}
          <SecondaryButton title="Cancel" disabled={busy} onPress={() => setSelected(null)} />
        </>
      ) : (
        <>
          <ScreenTitle title="Time you meant to spend." description="Pick an app, give it one rule. Guarded apps sit at the top." />

          <View>
            <SectionLabel className="mb-2.5">GUARDED</SectionLabel>
            {readState !== 'ready' || currentLimits === null || currentIntents === null || currentRolling === null ? (
              <Card>
                <Text className="text-[13px] leading-[19px] text-muted">
                  {readState === 'loading' ? 'Reading your app rules.' : 'The app rules could not be read.'}
                </Text>
              </Card>
            ) : guarded.length === 0 ? (
              <Card>
                <Text className="text-[13px] leading-[19px] text-muted">No app has a rule yet. Pick one below.</Text>
              </Card>
            ) : (
              <View className="gap-2.5">
                {guarded.map((entry) => (
                  <GuardedCard key={entry.packageName} entry={entry} disabled={busy} onEdit={() => selectApp({ packageName: entry.packageName, label: entry.label })} />
                ))}
              </View>
            )}
          </View>

          <View>
            <SectionLabel className="mb-2.5">ALL APPS</SectionLabel>
            <TextInput
              accessibilityLabel="Search apps"
              autoCapitalize="none"
              autoCorrect={false}
              className="mb-2.5 rounded-2xl border border-line bg-panel px-4 py-3.5 text-[15px] text-copy"
              editable={!busy && readState === 'ready'}
              onChangeText={setSearch}
              placeholder="Search"
              placeholderTextColor={colors.faint}
              value={search}
            />
            {unguarded.length === 0 ? (
              <Card>
                <Text className="text-[13px] leading-[19px] text-muted">
                  {readState === 'loading' ? 'Reading the apps on this phone.' : 'No app matches that.'}
                </Text>
              </Card>
            ) : (
              <RowGroup>
                {unguarded.map((app) => (
                  <Row key={app.packageName} label={app.label} detail={app.packageName} value="ADD" tone="accent" disabled={busy} onPress={() => selectApp(app)} />
                ))}
              </RowGroup>
            )}
          </View>
        </>
      )}

      <Text className="text-center text-[12px] leading-[18px] text-faint">Budgets reset at midnight. Visits ask first. Allowances refill over time.</Text>
      <ErrorNote message={error} />
    </Screen>
  );
}

/** One guarded app with its rule summary. Editing lives behind the button. */
function GuardedCard({ entry, disabled, onEdit }: { entry: GuardedEntry; disabled: boolean; onEdit: () => void }) {
  const summaries: string[] = [];
  let pill = 'RULE';
  let spentRatio: number | null = null;
  let spentTone: 'accent' | 'danger' = 'accent';
  if (entry.limit) {
    const used = Math.floor(entry.limit.usedMs / 60_000);
    summaries.push(`${used} of ${entry.limit.minutes} minutes today`);
    pill = MODE_LABEL.daily;
    spentRatio = Math.min(100, Math.round((entry.limit.usedMs / (entry.limit.minutes * 60_000)) * 100));
    if (entry.limit.usedMs >= entry.limit.minutes * 60_000) {
      pill = 'SPENT';
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
    spentRatio = Math.min(100, Math.round((entry.rolling.usedMs / (entry.rolling.allowanceMinutes * 60_000)) * 100));
    if (entry.rolling.usedMs >= entry.rolling.allowanceMinutes * 60_000) {
      pill = 'SPENT';
      spentTone = 'danger';
    }
  }

  return (
    <Card>
      <View className="flex-row items-start justify-between">
        <View className="flex-1 pr-3">
          <Text className="text-[15px] font-semibold text-copy">{entry.label}</Text>
          {summaries.map((summary) => (
            <Text key={summary} className="mt-0.5 text-[13px] text-muted">
              {summary}
            </Text>
          ))}
        </View>
        <StatusPill label={pill} tone={spentTone} />
      </View>
      {spentRatio !== null ? (
        <View className="mt-3 h-1 overflow-hidden rounded-full bg-track">
          <View className={`h-full rounded-full ${spentTone === 'danger' ? 'bg-danger' : 'bg-accent'}`} style={{ width: `${spentRatio}%` }} />
        </View>
      ) : null}
      <SecondaryButton className="mt-3.5" title="Edit" disabled={disabled} onPress={onEdit} />
    </Card>
  );
}

function OptionRow({ options, selected, disabled, onSelect, format }: { options: number[]; selected: number; disabled: boolean; onSelect: (value: number) => void; format?: (value: number) => string }) {
  return (
    <View className="flex-row flex-wrap gap-2">
      {options.map((value) => (
        <Pressable
          key={value}
          accessibilityRole="radio"
          accessibilityState={{ selected: selected === value, disabled }}
          className={`min-w-[56px] items-center rounded-xl border px-3 py-2 ${selected === value ? 'border-accent bg-accent' : 'border-line bg-panel2'} ${disabled ? 'opacity-40' : 'active:opacity-70'}`}
          disabled={disabled}
          onPress={() => onSelect(value)}>
          <Text className={`text-[14px] font-bold ${selected === value ? 'text-onAccent' : 'text-muted'}`}>{format ? format(value) : `${value}m`}</Text>
        </Pressable>
      ))}
    </View>
  );
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
  ];
  return (
    <View className="gap-2">
      {modes.map(({ mode, title, detail }) => (
        <Pressable
          key={mode}
          accessibilityRole="radio"
          accessibilityState={{ selected: selected === mode, disabled }}
          className={`flex-row items-center justify-between rounded-xl border px-4 py-3 ${selected === mode ? 'border-accent bg-accent' : 'border-line bg-panel2'} ${disabled ? 'opacity-40' : 'active:opacity-70'}`}
          disabled={disabled}
          onPress={() => onSelect(mode)}>
          <View>
            <Text className={`text-[15px] font-semibold ${selected === mode ? 'text-onAccent' : 'text-copy'}`}>{title}</Text>
            <Text className={`mt-0.5 text-[13px] ${selected === mode ? 'text-onAccent' : 'text-muted'}`}>{detail}</Text>
          </View>
          <StatusPill label={MODE_LABEL[mode]} tone={selected === mode ? 'neutral' : 'accent'} />
        </Pressable>
      ))}
    </View>
  );
}
