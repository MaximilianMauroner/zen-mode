import { Card } from '@/components/ui/card';
import { ChipGroup, ChoiceCard } from '@/components/ui/choice';
import { PrimaryButton, SecondaryButton } from '@/components/ui/button';
import { ErrorNote, Screen, ScreenTitle } from '@/components/ui/screen';
import { PasscodeInput } from '@/components/ui/weakening-gate';
import { LOCK_DAY_OPTIONS, passcodeEntryRefusal, readLockState } from '@/features/protection/lock';
import { acceptSetupConsent, finishAndroidSetup, hasCompletedSetup, type SetupLock, type SetupPlan } from '@/features/protection/setup';
import { KIDS_DAILY_CAP_MINUTES, RECOMMENDED_DEFAULTS_SUMMARY, type GuidedSetupMode } from '@/features/protection/recommended-defaults';
import { openAccessibilitySettings } from '@/features/protection/native';
import { colors } from '@/theme/colors';
import { Check, Eye } from 'lucide-react-native';
import { openURL } from 'expo-linking';
import { router } from 'expo-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import { AppState, Platform, Pressable, Text, View } from 'react-native';

type SetupMode = GuidedSetupMode | 'customize';
type StartingRules = 'recommended' | 'customize';
type SetupStep =
  | { step: 'loading' }
  | { step: 'read-error' }
  | { step: 'family-link' }
  | { step: 'mode'; mode?: SetupMode }
  | { step: 'starting-rules'; choice?: StartingRules }
  | { step: 'lock'; mode: GuidedSetupMode; lock?: SetupLock }
  | { step: 'access'; plan: SetupPlan; requested: boolean };

const SETUP_MODES = [
  { mode: 'kids', title: 'Kids', detail: `Timed visits, a ${KIDS_DAILY_CAP_MINUTES}-minute daily cap per app, and a Kids end screen.` },
  { mode: 'focus', title: 'Focus', detail: 'Timed visits and a Focus end screen for your own device.' },
  { mode: 'customize', title: 'Customize', detail: 'Review the healthy defaults or choose your own rules.' },
] as const;
const STARTING_RULES = [
  { value: 'recommended', label: 'Use healthy defaults' },
  { value: 'customize', label: 'Customize' },
] as const;

export default function SetupScreen() {
  const android = Platform.OS === 'android';
  const [flow, setFlow] = useState<SetupStep>(android ? { step: 'loading' } : { step: 'mode' });
  const [consented, setConsented] = useState(false);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const [retryCount, setRetryCount] = useState(0);
  const [showDetails, setShowDetails] = useState(false);
  const working = useRef(false);

  useEffect(() => {
    if (!android) return;
    let mounted = true;
    void (async () => {
      try {
        const completed = await hasCompletedSetup();
        if (!mounted) return;
        if (completed) {
          router.replace('/');
          return;
        }
        const lock = await readLockState();
        if (mounted) setFlow(lock.kind === 'open'
          ? { step: 'mode' }
          : { step: 'access', plan: { mode: 'restore' }, requested: false });
      } catch {
        if (mounted) {
          setFlow({ step: 'read-error' });
          setError('Could not read your setup. Try again.');
        }
      }
    })();

    return () => {
      mounted = false;
    };
  }, [android, retryCount]);

  const run = useCallback(async (action: () => Promise<unknown>, fallback: string) => {
    if (working.current) return;
    working.current = true;
    setSaving(true);
    setError('');
    try {
      await action();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : fallback);
    } finally {
      setSaving(false);
      working.current = false;
    }
  }, []);

  const checkAccess = useCallback(async () => {
    if (flow.step !== 'access' || !flow.requested) return;
    await run(async () => {
      const destination = await finishAndroidSetup(flow.plan);
      if (destination === 'family-link') setFlow({ step: 'family-link' });
      else router.replace(destination === 'customize' ? '/?setup=customize' : '/');
    }, 'Could not finish setup. Try again.');
  }, [flow, run]);

  useEffect(() => {
    if (flow.step !== 'access' || !flow.requested) return;
    const listener = AppState.addEventListener('change', (state) => {
      if (state === 'active') void checkAccess();
    });
    return () => listener.remove();
  }, [checkAccess, flow]);

  const moveTo = (next: SetupStep) => {
    setError('');
    setFlow(next);
  };

  const continueToAccess = (plan: SetupPlan) => {
    if ('lock' in plan && plan.lock.kind === 'passcode') {
      const refusal = passcodeEntryRefusal(plan.lock.passcode, plan.lock.confirmation);
      if (refusal) return setError(refusal);
    }
    moveTo({ step: 'access', plan, requested: false });
  };

  if (flow.step === 'loading' || flow.step === 'read-error') {
    return (
      <Screen>
        <ScreenTitle title="Set up Zen Mode" />
        {flow.step === 'loading' ? <Text className="text-body text-muted">Checking setup…</Text> : <>
          <ErrorNote message={error} />
          <PrimaryButton title="Try again" onPress={() => {
            moveTo({ step: 'loading' });
            setRetryCount((count) => count + 1);
          }} />
        </>}
      </Screen>
    );
  }

  if (flow.step === 'mode') {
    const mode = flow.mode;
    return (
      <Screen key="mode">
        <ScreenTitle title="Set up Zen Mode" description="Kids and Focus apply recommended rules and ask for a stop time in YouTube, Instagram, and X when those apps are installed." />
        <View accessibilityRole="radiogroup" accessibilityLabel="Setup mode" className="gap-3">
          {SETUP_MODES.map((option) => (
            <ChoiceCard key={option.mode} title={option.title} detail={option.detail} selected={mode === option.mode} onPress={() => moveTo({ step: 'mode', mode: option.mode })} />
          ))}
        </View>
        <PrimaryButton title="Continue" disabled={!mode} onPress={() => {
          if (mode === 'customize') moveTo({ step: 'starting-rules' });
          else if (mode) moveTo({ step: 'lock', mode });
        }} />
        <SecondaryButton title="Privacy" onPress={() => router.navigate('/privacy')} />
      </Screen>
    );
  }

  if (flow.step === 'starting-rules') {
    const choice = flow.choice;
    return (
      <Screen key="starting-rules">
        <ScreenTitle title="Starting rules" />
        <ChipGroup label="Starting rules" options={STARTING_RULES} selected={choice} onSelect={(value) => moveTo({ step: 'starting-rules', choice: value })} />
        {choice === 'recommended' ? (
          <View className="gap-1.5">
            {RECOMMENDED_DEFAULTS_SUMMARY.map((line) => (
              <View key={line} className="flex-row gap-2">
                <Check color={colors.accent} size={15} style={{ marginTop: 2 }} />
                <Text className="flex-1 text-support text-muted">{line}</Text>
              </View>
            ))}
          </View>
        ) : choice === 'customize' ? <Text className="text-support text-muted">Protection stays paused while you choose rules.</Text> : null}
        <PrimaryButton title="Continue" disabled={!choice} onPress={() => { if (choice) continueToAccess({ mode: choice }); }} />
        <SecondaryButton title="Back" onPress={() => moveTo({ step: 'mode', mode: 'customize' })} />
      </Screen>
    );
  }

  if (flow.step === 'lock') {
    const { mode, lock } = flow;
    const selectLock = (next: SetupLock) => moveTo({ step: 'lock', mode, lock: next });
    return (
      <Screen key="lock">
        <ScreenTitle title="Lock settings" description="Choose how to protect your rules from changes that weaken them." />
        <View accessibilityRole="radiogroup" accessibilityLabel="Lock type" className="gap-3">
          <ChoiceCard title="Passcode" detail="Enter the passcode to loosen rules." selected={lock?.kind === 'passcode'} onPress={() => {
            if (lock?.kind !== 'passcode') selectLock({ kind: 'passcode', passcode: '', confirmation: '' });
          }} />
          <ChoiceCard title="Time lock" detail="Wait for the lock to end before you loosen rules." selected={lock?.kind === 'time'} onPress={() => {
            if (lock?.kind !== 'time') selectLock({ kind: 'time', days: 7 });
          }} />
        </View>
        {lock?.kind === 'passcode' ? (
          <Card className="gap-3">
            <Text className="text-support text-muted">Use at least 6 characters.</Text>
            <PasscodeInput label="Passcode" value={lock.passcode} onChangeText={(passcode) => selectLock({ ...lock, passcode })} />
            <PasscodeInput label="Confirm passcode" value={lock.confirmation} onChangeText={(confirmation) => selectLock({ ...lock, confirmation })} />
          </Card>
        ) : null}
        {lock?.kind === 'time' ? (
          <ChipGroup label="Lock length" options={LOCK_DAY_OPTIONS.map((days) => ({ value: days, label: `${days}d` }))} selected={lock.days} onSelect={(days) => selectLock({ kind: 'time', days })} />
        ) : null}
        <ErrorNote message={error} />
        <PrimaryButton title="Continue" disabled={!lock} onPress={() => { if (lock) continueToAccess({ mode, lock }); }} />
        <SecondaryButton title="Back" onPress={() => moveTo({ step: 'mode', mode })} />
      </Screen>
    );
  }

  if (flow.step === 'family-link') {
    return (
      <Screen key="family-link">
        <ScreenTitle title="Keep Zen Mode on" />
        <Text className="text-body text-muted">Use Google Family Link to restrict your child from uninstalling Zen Mode or turning off its accessibility access.</Text>
        <SecondaryButton title="Open Google Family Link" disabled={saving} onPress={() => run(
          () => openURL('https://families.google/familylink/'),
          'Could not open Google Family Link.',
        )} />
        <ErrorNote message={error} />
        <PrimaryButton title="Finish setup" disabled={saving} onPress={() => router.replace('/')} />
      </Screen>
    );
  }

  const { plan, requested } = flow;
  const openAccess = () => {
    if (!consented) return setError('Read the note above and tick the box first.');
    return run(async () => {
      await acceptSetupConsent();
      moveTo({ step: 'access', plan, requested: true });
      await openAccessibilitySettings();
    }, 'Could not open Android settings.');
  };
  const back = () => {
    if ('lock' in plan) moveTo({ step: 'lock', mode: plan.mode, lock: plan.lock });
    else if (plan.mode !== 'restore') moveTo({ step: 'starting-rules', choice: plan.mode });
  };
  const selectionDisabled = !android || saving || requested;

  return (
    <Screen key="access">
      <ScreenTitle title="Set up Android access" />
      {!android ? <Text className="text-body text-muted">Protection requires the Android app.</Text> : null}
      {plan.mode === 'restore' ? <Text className="text-body text-muted">Grant access again to use your saved rules and settings lock.</Text> : null}
      <Card>
        <View className="flex-row items-center">
          <Eye color={colors.accent} size={18} />
          <Text accessibilityRole="header" className="ml-2.5 text-item font-semibold text-copy">Accessibility access</Text>
        </View>
        <Text className="mt-3 text-body text-muted">
          Zen Mode uses accessibility access to see supported feeds, the current app, and browser addresses when site blocking is on. It uses this information to apply your rules. Screen text and addresses stay on your phone.
        </Text>
        <Pressable accessibilityRole="button" accessibilityState={{ expanded: showDetails, disabled: saving }} disabled={saving} onPress={() => setShowDetails((value) => !value)} className="mt-1 min-h-[48px] justify-center self-start">
          <Text className="text-support font-semibold text-accent">{showDetails ? 'Hide access details' : 'More about this access'}</Text>
        </Pressable>
        {showDetails ? <Text className="mb-1 text-support text-muted">When you add an app limit or timed visit, Zen Mode lists launchable apps on this device so you can choose one. It stores guard settings and local usage and detection summaries, not visited addresses, screen text, or the installed-app inventory. It does not inspect browser page content.</Text> : null}
        <Pressable
          accessibilityRole="checkbox"
          accessibilityState={{ checked: consented, disabled: selectionDisabled }}
          className={`mt-3 min-h-[48px] flex-row items-center rounded-2xl border px-3.5 ${consented ? 'border-accent bg-accentBg' : 'border-line2 bg-panel2'} ${selectionDisabled ? 'opacity-40' : 'active:opacity-70'}`}
          disabled={selectionDisabled}
          onPress={() => setConsented((value) => !value)}>
          <View className={`h-5 w-5 items-center justify-center rounded-md border-2 ${consented ? 'border-accent bg-accent' : 'border-muted'}`}>
            {consented ? <Check color={colors.onAccent} size={14} strokeWidth={3} /> : null}
          </View>
          <Text className="ml-3 flex-1 py-3 text-body font-medium text-copy">I understand, and I agree to this use.</Text>
        </Pressable>
      </Card>

      <ErrorNote message={error} />
      {requested ? <Text className="text-support text-muted">Setup is pending until Zen Mode confirms Android accessibility access. If you did not grant it, open Settings and try again.</Text> : null}
      <View className="gap-3">
        <PrimaryButton
          title={saving ? 'Checking access…' : requested ? 'Check Android access again' : 'Continue to Android access'}
          disabled={!android || saving}
          onPress={requested ? checkAccess : openAccess}
        />
        {requested ? <SecondaryButton title="Open Android settings" disabled={saving} onPress={() => run(openAccessibilitySettings, 'Could not open Android settings.')} /> : null}
        {!requested && plan.mode !== 'restore' ? <SecondaryButton title="Back" disabled={saving} onPress={back} /> : null}
        <SecondaryButton title="Privacy" disabled={saving} onPress={() => router.navigate('/privacy')} />
      </View>
    </Screen>
  );
}
