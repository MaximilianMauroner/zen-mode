import { DangerButton, PrimaryButton, SecondaryButton } from '@/components/ui/button';
import { Card, CardHeader, SectionLabel } from '@/components/ui/card';
import { ErrorNote, Screen, ScreenHeader, ScreenTitle } from '@/components/ui/screen';
import {
  armLock,
  cancelUnlock,
  formatRemaining,
  LOCK_DAY_OPTIONS,
  readLockState,
  requestUnlock,
  type LockState,
} from '@/features/protection/lock';
import { colors } from '@/theme/colors';
import { Lock, TriangleAlert } from 'lucide-react-native';
import { router, useFocusEffect } from 'expo-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Pressable, Text, View } from 'react-native';

type ReadState = 'loading' | 'ready' | 'error';

export default function LockScreen() {
  const [lock, setLock] = useState<LockState | null>(null);
  const [readState, setReadState] = useState<ReadState>('loading');
  const [selectedDays, setSelectedDays] = useState(7);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [now, setNow] = useState(() => Date.now());
  const busyRef = useRef(false);
  const refreshInFlightRef = useRef(false);

  const refresh = useCallback(async () => {
    if (refreshInFlightRef.current) return;
    refreshInFlightRef.current = true;
    setReadState('loading');
    setLock(null);
    setError('');
    try {
      setLock(await readLockState());
      setReadState('ready');
    } catch {
      setLock(null);
      setReadState('error');
      setError('The lock could not be read.');
    } finally {
      refreshInFlightRef.current = false;
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      void refresh();
    }, [refresh]),
  );

  // Countdowns are minute-grained, so a minute tick is enough to keep them true.
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 60_000);
    return () => clearInterval(timer);
  }, []);

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

  const goBack = () => runAction(async () => router.back(), 'Could not go back.');

  const currentLock = readState === 'ready' ? lock : null;
  const pill =
    currentLock === null
      ? { label: readState === 'loading' ? 'CHECKING' : 'UNAVAILABLE', tone: 'neutral' as const }
      : currentLock.kind === 'open'
        ? { label: 'OPEN', tone: 'neutral' as const }
        : currentLock.kind === 'pending'
          ? { label: 'UNLOCKING', tone: 'danger' as const }
          : { label: 'LOCKED', tone: 'accent' as const, solid: true };

  return (
    <Screen>
      <ScreenHeader label="LOCK" onBack={goBack} backDisabled={busy} pill={pill} />

      <ScreenTitle
        title={currentLock?.kind === 'pending' ? 'Unlocking.' : currentLock?.kind === 'locked' ? 'Locked in.' : 'Commit to it.'}
        description={
          currentLock === null
            ? readState === 'loading'
              ? 'Reading the lock.'
              : 'Zen Mode cannot show or change the lock until it can read this.'
            : currentLock.kind === 'open'
              ? 'Pick how long to hold your limits. While the lock is on you can tighten them, but not loosen them.'
              : currentLock.kind === 'locked'
                ? 'Tighten a limit whenever you like. Loosening one means asking to unlock, then waiting a day.'
                : 'The wait is running. Your limits hold until it ends.'
        }
      />

      {currentLock !== null && currentLock.kind !== 'open' ? (
        <Card emphasis={currentLock.kind === 'locked'}>
          <CardHeader
            label={currentLock.kind === 'pending' ? 'OPENS IN' : 'HOLDS FOR'}
            icon={<Lock color={colors.accent} size={15} />}
          />
          <Text className="mt-3 text-[40px] font-bold leading-[46px] tracking-tight text-accent" style={{ fontVariant: ['tabular-nums'] }}>
            {formatRemaining(currentLock.endsAt, now)}
          </Text>
          <Text className="mt-1 text-[13px] leading-[19px] text-muted">
            {currentLock.kind === 'pending'
              ? 'Changed your mind? Keep it locked and nothing happens.'
              : 'Tightening works right now. Loosening needs the wait.'}
          </Text>
          {currentLock.kind === 'pending' ? (
            <PrimaryButton className="mt-4" title={busy ? 'Working…' : 'Keep it locked'} disabled={busy} onPress={keepLocked} />
          ) : null}
        </Card>
      ) : null}

      <View>
        <SectionLabel className="mb-2.5">{currentLock !== null && currentLock.kind !== 'open' ? 'EXTEND' : 'LOCK FOR'}</SectionLabel>
        <Card>
          <View className="flex-row flex-wrap gap-2">
            {LOCK_DAY_OPTIONS.map((days) => (
              <Pressable
                key={days}
                accessibilityRole="radio"
                accessibilityState={{ selected: selectedDays === days, disabled: busy }}
                className={`min-w-[56px] items-center rounded-xl border px-3 py-2 ${selectedDays === days ? 'border-accent bg-accent' : 'border-line bg-panel2'} ${busy ? 'opacity-40' : 'active:opacity-70'}`}
                disabled={busy}
                onPress={() => setSelectedDays(days)}>
                <Text className={`text-[14px] font-bold ${selectedDays === days ? 'text-onAccent' : 'text-muted'}`}>{days}d</Text>
              </Pressable>
            ))}
          </View>
          {currentLock !== null && currentLock.kind !== 'open' ? (
            <SecondaryButton
              className="mt-4"
              title={busy ? 'Working…' : `Extend to ${selectedDays} days`}
              disabled={busy || readState !== 'ready'}
              onPress={arm}
            />
          ) : (
            <PrimaryButton
              className="mt-4"
              title={busy ? 'Working…' : `Lock for ${selectedDays} days`}
              disabled={busy || readState !== 'ready'}
              onPress={arm}
            />
          )}
        </Card>
      </View>

      {currentLock?.kind === 'locked' ? (
        <DangerButton title={busy ? 'Working…' : 'Ask to unlock'} disabled={busy} onPress={askToUnlock} />
      ) : null}

      <View className="flex-row items-start rounded-2xl border border-line bg-panel2 px-4 py-3">
        <View className="mt-0.5">
          <TriangleAlert color={colors.faint} size={16} />
        </View>
        <Text className="ml-3 flex-1 text-[12px] leading-[18px] text-faint">
          The lock trusts this phone&apos;s clock. Changing the date or uninstalling Zen Mode gets around it.
        </Text>
      </View>
      <ErrorNote message={error} />
    </Screen>
  );
}
