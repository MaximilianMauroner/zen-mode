import { hasCompletedSetup, markSetupComplete } from '@/features/protection/credential';
import { openAccessibilitySettings, setInstagramObservationMode, setNativeProtectionEnabled, setObservationMode } from '@/features/protection/native';
import { Check, Eye, ShieldCheck } from 'lucide-react-native';
import { router } from 'expo-router';
import { useEffect, useState } from 'react';
import { Pressable, ScrollView, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

export default function SetupScreen() {
  const [consented, setConsented] = useState(false);
  const [error, setError] = useState('');
  const [setupReadState, setSetupReadState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [saving, setSaving] = useState(false);
  const [retryCount, setRetryCount] = useState(0);

  useEffect(() => {
    let mounted = true;
    void (async () => {
      try {
        if (await hasCompletedSetup()) {
          router.replace('/');
        } else if (mounted) {
          setSetupReadState('ready');
        }
      } catch {
        if (mounted) {
          setSetupReadState('error');
          setError('Setup status could not be read. Try again.');
        }
      }
    })();

    return () => {
      mounted = false;
    };
  }, [retryCount]);

  const retrySetupCheck = () => {
    if (saving || setupReadState === 'loading') return;
    setError('');
    setSetupReadState('loading');
    setRetryCount((count) => count + 1);
  };

  const finishSetup = async () => {
    if (setupReadState !== 'ready' || saving) return;
    setError('');
    if (!consented) return setError('Read and accept the accessibility disclosure first.');
    setSaving(true);
    try {
      await setNativeProtectionEnabled(true);
      await setObservationMode(true);
      await setInstagramObservationMode(true);
      await openAccessibilitySettings();
      await markSetupComplete();
      router.replace('/');
    } catch (setupError) {
      setError(setupError instanceof Error ? setupError.message : 'Setup could not be completed.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <SafeAreaView className="flex-1 bg-night">
      <ScrollView contentContainerClassName="mx-auto w-full max-w-xl px-4 pb-12 pt-4">
          <View className="h-10 w-10 items-center justify-center rounded-xl border border-line bg-panel2">
            <ShieldCheck color="#A7E782" size={21} />
          </View>
          <Text className="mt-7 text-4xl font-semibold tracking-tight text-copy">Set up protection</Text>
          <Text className="mt-3 text-base leading-6 text-muted">
            Zen Mode watches YouTube for Shorts and Instagram for Reels, Home-feed limits, and Explore. Messages and intentional content stay available.
          </Text>

          <View className="mt-7 rounded-3xl border border-dangerLine bg-panel p-4">
            <View className="flex-row items-center">
              <Eye color="#FF9A79" size={20} />
              <Text className="ml-3 text-base font-semibold text-copy">Accessibility access</Text>
            </View>
            <Text className="mt-4 leading-6 text-muted">
              To detect YouTube Shorts and Instagram feed surfaces, Zen Mode reads the visible accessibility structure of those two apps. It uses that information only on this device to recognize guarded screens.
            </Text>
            <Text className="mt-3 leading-6 text-muted">
              Zen Mode does not inspect the content or accessibility trees of other apps, collect typed text, store page contents, send screen contents, or use this access for analytics.
            </Text>
            <Pressable
              accessibilityRole="checkbox"
              accessibilityState={{ checked: consented, disabled: setupReadState !== 'ready' || saving }}
              className="mt-5 flex-row items-center"
              disabled={setupReadState !== 'ready' || saving}
              onPress={() => setConsented((value) => !value)}>
              <View className={`h-6 w-6 items-center justify-center rounded-lg border ${consented ? 'border-accent bg-accent' : 'border-line bg-panel2'}`}>
                {consented && <Check color="#0B1609" size={15} strokeWidth={3} />}
              </View>
              <Text className="ml-3 flex-1 text-sm font-medium leading-5 text-copy">I understand and consent to this specific use.</Text>
            </Pressable>
          </View>

          {error ? <Text className="mt-4 text-sm font-medium text-danger">{error}</Text> : null}
          <Pressable
            className={`mt-6 items-center rounded-2xl py-3.5 ${saving ? 'bg-panel2' : 'bg-accent active:opacity-80'}`}
            disabled={setupReadState === 'loading' || saving}
            onPress={setupReadState === 'error' ? retrySetupCheck : finishSetup}>
            <Text className="text-base font-bold text-onAccent">
              {setupReadState === 'loading' ? 'Checking…' : saving ? 'Saving…' : setupReadState === 'error' ? 'Retry' : 'Enable protection'}
            </Text>
          </Pressable>
          <Text className="mt-4 text-center text-xs leading-5 text-muted">
            This version cannot prevent you from disabling the service in Android Settings or uninstalling the app.
          </Text>
      </ScrollView>
    </SafeAreaView>
  );
}
