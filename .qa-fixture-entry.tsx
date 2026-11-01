// Acceptance-only entry. The normal APK still starts expo-router/entry.
import React, { useEffect, useRef, useState } from 'react';
import { AppRegistry, Pressable, StyleSheet, Text, ToastAndroid, View } from 'react-native';
import { getAppLimits, getInstalledApps, getIntentApps, getRollingLimits, getZenGuardStatus, setAppLimit } from './src/features/protection/native';
import { armLock, readLockState } from './src/features/protection/lock';

const CLOCK = 'com.sec.android.app.clockpackage';
type Actor = 'rule' | 'lock';
async function requireFixtureState() {
  const [status, apps, daily, visits, rolling, lock] = await Promise.all([
    getZenGuardStatus(), getInstalledApps(), getAppLimits(), getIntentApps(), getRollingLimits(), readLockState(),
  ]);
  if (!status.available || !status.currentConsent || !status.serviceEnabled || !status.protectionEnabled) {
    throw new Error('Complete consent/access and resume QA protection first.');
  }
  if (lock.kind !== 'open') throw new Error('QA lock must be open. No fixture will bypass a lock.');
  if (!apps.some((app) => app.packageName === CLOCK)) throw new Error('Samsung Clock is not available.');
  if (daily.find((rule) => rule.packageName === CLOCK)?.minutes !== 5 ||
      visits.some((rule) => rule.packageName === CLOCK) || rolling.some((rule) => rule.packageName === CLOCK)) {
    throw new Error('Use the product UI to save Clock daily 5m with no timed/rolling rule first.');
  }
}
function FixturePanel() {
  const [expanded, setExpanded] = useState(false);
  const [message, setMessage] = useState('Idle');
  const [running, setRunning] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const busy = useRef(false);
  useEffect(() => () => { if (timer.current) clearTimeout(timer.current); }, []);
  const cancel = () => {
    if (timer.current) clearTimeout(timer.current);
    timer.current = null; busy.current = false; setRunning(false); setMessage('Cancelled; no queued fixture');
  };
  const schedule = async (actor: Actor) => {
    if (busy.current) return;
    busy.current = true;
    try {
      await requireFixtureState();
      const due = new Date(Date.now() + 60_000).toLocaleTimeString();
      setMessage(`${actor === 'rule' ? 'Clock 5→15m' : 'Arm 1-day lock'} at ${due}; open challenge NOW`);
      setRunning(true); setExpanded(false);
      timer.current = setTimeout(() => {
        timer.current = null;
        void (async () => {
          try {
            await requireFixtureState();
            if (actor === 'rule') {
              await setAppLimit(CLOCK, 15);
              const saved = (await getAppLimits()).find((rule) => rule.packageName === CLOCK);
              if (saved?.minutes !== 15) throw new Error('Native readback did not confirm 15m.');
              setMessage('DONE: native Clock daily readback = 15m');
              ToastAndroid.show('QA actor: Clock daily limit is now 15 minutes. Confirm the original edit now.', ToastAndroid.LONG);
            } else {
              await armLock(1);
              const saved = await readLockState();
              if (saved.kind !== 'locked') throw new Error('SecureStore readback did not confirm the lock.');
              setMessage('DONE: source lock API/readback = locked, 1 day');
              ToastAndroid.show('QA actor: the one-day lock is armed. Confirm the original edit now.', ToastAndroid.LONG);
            }
          } catch (cause) {
            const text = cause instanceof Error ? cause.message : 'Fixture failed';
            setMessage(`REFUSED: ${text}`); ToastAndroid.show(`QA fixture refused: ${text}`, ToastAndroid.LONG);
          } finally { busy.current = false; setRunning(false); }
        })();
      }, 60_000);
    } catch (cause) {
      busy.current = false;
      setMessage(cause instanceof Error ? cause.message : 'Fixture unavailable');
    }
  };
  return <View style={styles.panel}>
    <Pressable accessibilityRole="button" onPress={() => setExpanded((value) => !value)} style={styles.button}>
      <Text style={styles.text}>{expanded ? 'Close QA fixture controls' : 'QA race fixtures'}</Text>
    </Pressable>
    {expanded ? <>
      <Text accessibilityRole="header" style={styles.title}>QA RACE FIXTURES — not product UI</Text>
      <Text accessibilityLiveRegion="polite" style={styles.text}>{message}</Text>
      <Text style={styles.text}>Synthetic actor only. Clock daily 5m, lock open, access and protection required. Stage BEFORE Save 30m; stay in the challenge for 60s. Ordinary cancellation tests use the clean APK.</Text>
      <Pressable accessibilityRole="button" disabled={running} onPress={() => void schedule('rule')} style={styles.button}><Text style={styles.text}>In 60s: change QA Clock 5→15m</Text></Pressable>
      <Pressable accessibilityRole="button" disabled={running} onPress={() => void schedule('lock')} style={styles.button}><Text style={styles.text}>In 60s: arm QA 1-day lock</Text></Pressable>
      <Pressable accessibilityRole="button" onPress={cancel} style={styles.button}><Text style={styles.text}>Cancel pending fixture</Text></Pressable>
    </> : null}
  </View>;
}
const styles = StyleSheet.create({
  panel: { position: 'absolute', top: 56, right: 8, maxWidth: 290, padding: 8, borderRadius: 12, backgroundColor: '#351F46', borderWidth: 2, borderColor: '#D6B5FF', zIndex: 1000 },
  button: { minHeight: 48, justifyContent: 'center', paddingHorizontal: 8, marginVertical: 3, borderWidth: 1, borderColor: '#D6B5FF', borderRadius: 8 },
  text: { color: '#FFFFFF', fontSize: 13, lineHeight: 19 }, title: { color: '#FFFFFF', fontWeight: '700', fontSize: 14 },
});
const originalRegister = AppRegistry.registerComponent;
AppRegistry.registerComponent = (key, provider, section) => originalRegister(key, () => {
  const ProductRoot = provider();
  return function FixtureRoot(props: Record<string, unknown>) {
    return <><ProductRoot {...props} /><FixturePanel /></>;
  };
}, section);
// Synchronous registration must occur after the acceptance wrapper is installed.
// eslint-disable-next-line @typescript-eslint/no-require-imports
require('expo-router/entry');
