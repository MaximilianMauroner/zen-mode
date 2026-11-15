import { useCallback, useEffect, useRef, useState } from 'react';
import { Platform, Text, TextInput, View } from 'react-native';
import { router, useFocusEffect } from 'expo-router';

import { PrimaryButton, SecondaryButton } from '@/components/ui/button';
import { ChipGroup } from '@/components/ui/choice';
import { ErrorNote, Screen, ScreenHeader } from '@/components/ui/screen';
import { clearDiagnostics, getDiagnostics, shareDiagnostics, startDiagnostics, stopDiagnostics, type DiagnosticRecording } from '@/features/protection/native';
import { colors } from '@/theme/colors';

type Unit = 'seconds' | 'minutes';
const UNITS = [{ value: 'seconds', label: 'Seconds' }, { value: 'minutes', label: 'Minutes' }] as const;

export default function DiagnosticsScreen() {
  const [recording, setRecording] = useState<DiagnosticRecording | null>(null);
  const [duration, setDuration] = useState('1');
  const [unit, setUnit] = useState<Unit>('minutes');
  const [showRecording, setShowRecording] = useState(false);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const [error, setError] = useState<string | null>(null);
  const refresh = useCallback(async () => setRecording(await getDiagnostics()), []);

  useFocusEffect(useCallback(() => {
    if (Platform.OS !== 'android') return;
    void refresh().catch(() => setError('Could not read the diagnostic recording.'));
  }, [refresh]));
  useEffect(() => {
    if (recording?.state !== 'recording') return;
    const timer = setInterval(() => { void refresh().catch(() => setError('Could not read the diagnostic recording.')); }, 1000);
    return () => clearInterval(timer);
  }, [recording?.state, refresh]);

  const run = (task: () => Promise<void>) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    void task().then(refresh).catch(() => setError('The action could not finish. Try again.')).finally(() => {
      busyRef.current = false;
      setBusy(false);
    });
  };
  const amount = /^\d+$/.test(duration) ? Number(duration) : 0;
  const seconds = amount * (unit === 'minutes' ? 60 : 1);
  const validDuration = Number.isSafeInteger(seconds) && seconds >= 1 && seconds <= 3600;
  const remaining = recording?.state === 'recording' ? recording.remainingSeconds : 0;

  return (
    <Screen>
      <ScreenHeader title="Diagnostic recording" onBack={() => router.back()} />
      {Platform.OS !== 'android' ? <Text className="text-copy">Diagnostic recording is available in the Android app.</Text> : (
        <>
          <Text className="text-copy">Record guard events while you reproduce a problem. Recording starts only when you choose. It stays on this device until you share it. Guard events require Android access.</Text>
          <Text className="text-support text-muted">The recording includes rule settings, timing, app categories, known screen markers, and guard actions. It does not include post text, messages, website addresses, or screenshots. It holds up to 800 events.</Text>
          <View className="gap-3">
            <Text accessibilityRole="header" className="text-title font-semibold text-copy">Duration</Text>
            <TextInput accessibilityLabel="Recording duration" keyboardType="number-pad" value={duration} onChangeText={setDuration} editable={!busy && recording?.state !== 'recording'} placeholderTextColor={colors.faint} className="min-h-[48px] rounded-2xl border border-line2 bg-night px-4 py-3 text-body text-copy" />
            <ChipGroup label="Duration unit" options={UNITS} selected={unit} disabled={busy || recording?.state === 'recording'} onSelect={setUnit} />
            <Text className="text-support text-muted">Choose 1 second to 60 minutes. A new recording replaces the previous one.</Text>
            <PrimaryButton title="Start recording" disabled={busy || recording === null || recording.state === 'recording' || !validDuration} onPress={() => run(async () => { setShowRecording(false); await startDiagnostics(seconds); })} />
          </View>
          <View className="gap-3">
            <Text accessibilityRole="header" className="text-title font-semibold text-copy">Recording</Text>
            <Text accessibilityLiveRegion="polite" className="text-copy">{recording === null ? 'Checking recording…' : recording.state === 'recording' ? `Recording. About ${remaining} seconds left.` : recording.state === 'complete' ? 'Recording complete.' : 'No recording saved.'}</Text>
            {recording?.state === 'recording' ? <SecondaryButton title="Stop recording" disabled={busy} onPress={() => run(stopDiagnostics)} /> : null}
            {recording?.state === 'complete' ? (
              <>
                <SecondaryButton title={showRecording ? 'Hide recording' : 'Review recording'} disabled={busy} onPress={() => setShowRecording(!showRecording)} />
                {showRecording ? <Text selectable className="rounded-2xl border border-line2 bg-night p-4 text-support text-copy">{recording.json}</Text> : null}
                <SecondaryButton title="Share recording" disabled={busy} onPress={() => run(() => shareDiagnostics())} />
                <SecondaryButton title="Delete recording" disabled={busy} onPress={() => run(clearDiagnostics)} />
              </>
            ) : null}
          </View>
          {error ? <ErrorNote message={error} /> : null}
        </>
      )}
    </Screen>
  );
}
