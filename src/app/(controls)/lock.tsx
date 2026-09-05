import { DangerButton, PrimaryButton, SecondaryButton } from '@/components/ui/button';
import { Card, CardHeader, SectionLabel } from '@/components/ui/card';
import { TopTabs } from 'expo-router/js-top-tabs';
import { ErrorNote, Screen } from '@/components/ui/screen';
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
import { Lock } from 'lucide-react-native';
import { useFocusEffect } from 'expo-router';
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

  // Warm inactive tabs after the first frame without delaying the visible screen.
  useEffect(() => {
    const frame = requestAnimationFrame(() => { void refresh(); });
    return () => cancelAnimationFrame(frame);
  }, [refresh]);

  useFocusEffect(
    useCallback(() => {
      void refresh();
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

  const currentLock = lock;
  const controlsDisabled = busy || readState !== 'ready';

  return (
    <Screen edges={[]}>
      <TopTabs.Screen options={{ swipeEnabled: !busy }} />

      <Text accessibilityRole="header" className="text-[18px] font-semibold text-copy">{currentLock === null ? readState === 'loading' ? 'Reading lock…' : 'Lock unavailable' : currentLock.kind === 'pending' ? 'Unlock requested' : currentLock.kind === 'locked' ? 'Settings locked' : 'Settings unlocked'}</Text>

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
            <PrimaryButton className="mt-4" title={busy ? 'Working…' : 'Keep it locked'} disabled={controlsDisabled} onPress={keepLocked} />
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
                accessibilityState={{ selected: selectedDays === days, disabled: controlsDisabled }}
                className={`min-h-11 min-w-[56px] items-center rounded-xl border px-3 py-2 ${selectedDays === days ? 'border-accent bg-accent' : 'border-line bg-panel2'} ${controlsDisabled ? 'opacity-40' : 'active:opacity-70'}`}
                disabled={controlsDisabled}
                onPress={() => setSelectedDays(days)}>
                <Text className={`text-[14px] font-bold ${selectedDays === days ? 'text-onAccent' : 'text-muted'}`}>{days}d</Text>
              </Pressable>
            ))}
          </View>
          {currentLock !== null && currentLock.kind !== 'open' ? (
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

      {currentLock?.kind === 'locked' ? (
        <DangerButton title={busy ? 'Working…' : 'Ask to unlock'} disabled={controlsDisabled} onPress={askToUnlock} />
      ) : null}

      <Text className="text-[12px] leading-[18px] text-muted">{currentLock?.kind === 'locked' || currentLock?.kind === 'pending' ? 'Unlocking takes at least 24 hours and cannot end before the lock expires.' : 'You can tighten limits while locked.'}</Text>
      <ErrorNote message={error} />
    </Screen>
  );
}
