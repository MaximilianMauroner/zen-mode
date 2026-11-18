import { DangerButton, PrimaryButton, SecondaryButton } from '@/components/ui/button';
import { Card, CardHeader, SectionLabel } from '@/components/ui/card';
import { ChipGroup } from '@/components/ui/choice';
import { TopTabs } from 'expo-router/js-top-tabs';
import { ErrorNote, Screen } from '@/components/ui/screen';
import { PasscodeInput, useWeakeningGate, WeakeningGateDialog } from '@/components/ui/weakening-gate';
import {
  armLock,
  cancelUnlock,
  formatRemaining,
  LOCK_DAY_OPTIONS,
  passcodeEntryRefusal,
  readLockState,
  requirePasscode,
  requestUnlock,
  UnreadableLockError,
  updatePasscode,
  type LockState,
} from '@/features/protection/lock';
import { colors } from '@/theme/colors';
import { Lock } from 'lucide-react-native';
import { useFocusEffect } from 'expo-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Text, View, type TextInput } from 'react-native';

type LockDisplay = LockState | { kind: 'unavailable' } | { kind: 'unreadable'; message: string };

const LOCK_TITLES: Record<LockDisplay['kind'], string> = {
  open: 'Settings unlocked',
  locked: 'Settings locked',
  pending: 'Unlock requested',
  passcode: 'Settings locked with a passcode',
  unavailable: 'Lock unavailable',
  unreadable: 'Unreadable lock',
};

export default function LockScreen() {
  const [lock, setLock] = useState<LockDisplay | null>(null);
  const [refreshing, setRefreshing] = useState(true);
  const [selectedDays, setSelectedDays] = useState(7);
  const [passcode, setPasscode] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [now, setNow] = useState(() => Date.now());
  const busyRef = useRef(false);
  const refreshInFlightRef = useRef(false);
  const confirmationInput = useRef<TextInput>(null);
  const gate = useWeakeningGate();

  const refresh = useCallback(async () => {
    if (refreshInFlightRef.current) return;
    refreshInFlightRef.current = true;
    setRefreshing(true);
    setError('');
    try {
      setLock(await readLockState());
    } catch (cause) {
      setLock(cause instanceof UnreadableLockError
        ? { kind: 'unreadable', message: cause.message }
        : { kind: 'unavailable' });
    } finally {
      setRefreshing(false);
      refreshInFlightRef.current = false;
    }
  }, []);

  // Warm inactive tabs after the first frame without delaying the visible screen.
  useEffect(() => {
    const frame = requestAnimationFrame(() => { void refresh(); });
    return () => cancelAnimationFrame(frame);
  }, [refresh]);

  useFocusEffect(
    useCallback(() => {
      void refresh();
      return () => {
        setPasscode('');
        setConfirmation('');
      };
    }, [refresh]),
  );

  // Inactive tabs do not keep ticking. Refresh the actual lock when it can expire.
  useFocusEffect(useCallback(() => {
    setNow(Date.now());
    const timer = setInterval(() => { setNow(Date.now()); if (!busyRef.current) void refresh(); }, 60_000);
    return () => clearInterval(timer);
  }, [refresh]));

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

  const arm = () =>
    runAction(async () => {
      await armLock(selectedDays);
      await refresh();
    }, 'The lock could not be set.');

  const askToUnlock = () =>
    runAction(async () => {
      await requestUnlock();
      await refresh();
    }, 'Could not ask to unlock.');

  const keepLocked = () =>
    runAction(async () => {
      await cancelUnlock();
      await refresh();
    }, 'Could not keep it locked.');

  const timeLock = lock?.kind === 'locked' || lock?.kind === 'pending' ? lock : null;
  const hasPasscode = lock?.kind === 'passcode';
  const controlsDisabled = busy || refreshing;
  const update = (entry: { passcode: string; confirmation: string } | null) =>
    runAction(async () => {
      if (entry !== null) {
        const refusal = passcodeEntryRefusal(entry.passcode, entry.confirmation);
        if (refusal) throw new Error(refusal);
      }
      let currentPasscode: string | undefined;
      if (hasPasscode) {
        const answer = await gate.request({
          kind: 'passcode',
          action: entry === null ? 'remove the passcode' : 'change the passcode',
          verifyPasscode: requirePasscode,
        });
        if (typeof answer !== 'string') return;
        currentPasscode = answer;
      }
      await updatePasscode(entry, currentPasscode);
      setPasscode('');
      setConfirmation('');
      await refresh();
    }, 'The passcode lock could not be updated.');

  return (
    <Screen edges={[]}>
      <TopTabs.Screen options={{ swipeEnabled: !busy }} />
      <WeakeningGateDialog gate={gate} />

      <Text accessibilityRole="header" className="text-title font-semibold text-copy">{lock ? LOCK_TITLES[lock.kind] : 'Reading lock…'}</Text>
      {lock?.kind === 'open' ? <Text className="text-body text-muted">Choose a time lock or a passcode.</Text> : null}
      {lock?.kind === 'unavailable' ? <ErrorNote message="The lock could not be read." /> : null}
      {lock?.kind === 'unreadable' ? <ErrorNote message={lock.message} /> : null}

      {timeLock ? (
        <Card emphasis={timeLock.kind === 'locked'}>
          <CardHeader
            label={timeLock.kind === 'pending' ? 'Opens in' : 'Holds for'}
            icon={<Lock color={colors.accent} size={15} />}
          />
          <Text className="mt-2 text-metric font-semibold tracking-tight text-accent" style={{ fontVariant: ['tabular-nums'] }}>
            {formatRemaining(timeLock.endsAt, now)}
          </Text>
          <Text className="mt-1 text-support text-muted">
            {timeLock.kind === 'pending'
              ? 'Changed your mind? Keep it locked and nothing happens.'
              : 'Tightening works right now. Loosening needs the wait.'}
          </Text>
          {timeLock.kind === 'pending' ? (
            <PrimaryButton className="mt-4" title={busy ? 'Working…' : 'Keep it locked'} disabled={controlsDisabled} onPress={keepLocked} />
          ) : null}
        </Card>
      ) : null}

      {timeLock || lock?.kind === 'open' ? (
        <View>
          <SectionLabel className="mb-2">{timeLock ? 'Extend' : 'Time lock'}</SectionLabel>
          <Card>
            <ChipGroup
              label="Lock length"
              options={LOCK_DAY_OPTIONS.map((days) => ({ value: days, label: `${days}d` }))}
              selected={selectedDays}
              disabled={controlsDisabled}
              onSelect={setSelectedDays}
            />
            {timeLock ? (
              <SecondaryButton
                className="mt-4"
                title={busy ? 'Working…' : `Extend to ${selectedDays} days`}
                disabled={controlsDisabled}
                onPress={arm}
              />
            ) : (
              <PrimaryButton
                className="mt-4"
                title={busy ? 'Working…' : `Lock for ${selectedDays} days`}
                disabled={controlsDisabled}
                onPress={arm}
              />
            )}
          </Card>
        </View>
      ) : null}

      {timeLock?.kind === 'locked' ? (
        <DangerButton title={busy ? 'Working…' : 'Ask to unlock'} disabled={controlsDisabled} onPress={askToUnlock} />
      ) : null}

      {hasPasscode || lock?.kind === 'open' ? (
        <View>
          <SectionLabel className="mb-2">Passcode</SectionLabel>
          <Card className="gap-3">
            <Text className="text-support text-muted">Use at least 6 characters.</Text>
            <PasscodeInput
              label={hasPasscode ? 'New passcode' : 'Passcode'}
              value={passcode}
              onChangeText={setPasscode}
              editable={!controlsDisabled}
              onSubmitEditing={() => confirmationInput.current?.focus()}
              returnKeyType="next"
              submitBehavior="submit"
            />
            <PasscodeInput ref={confirmationInput} label="Confirm passcode" value={confirmation} onChangeText={setConfirmation} editable={!controlsDisabled} />
            <PrimaryButton
              title={hasPasscode ? 'Change passcode' : 'Set passcode'}
              disabled={controlsDisabled || !passcode || !confirmation}
              onPress={() => update({ passcode, confirmation })}
            />
            {hasPasscode ? <DangerButton title="Remove passcode" disabled={controlsDisabled} onPress={() => update(null)} /> : null}
          </Card>
        </View>
      ) : null}

      <Text className="text-caption text-muted">{timeLock ? 'Unlocking takes at least 24 hours and cannot end before the lock expires.' : 'You can tighten limits while locked.'}</Text>
      <ErrorNote message={error} />
    </Screen>
  );
}
