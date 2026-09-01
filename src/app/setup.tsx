import { createProtectionPassword, hasProtectionPassword } from '@/features/protection/credential';
import { openAccessibilitySettings, setInstagramObservationMode, setNativeProtectionEnabled, setObservationMode } from '@/features/protection/native';
import { Check, Eye, LockKeyhole, ShieldCheck } from 'lucide-react-native';
import { router } from 'expo-router';
import { useEffect, useState } from 'react';
import { KeyboardAvoidingView, Platform, Pressable, ScrollView, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

export default function SetupScreen() {
  const [consented, setConsented] = useState(false);
  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    hasProtectionPassword().then((exists) => {
      if (exists) router.replace('/');
    });
  }, []);

  const finishSetup = async () => {
    setError('');
    if (!consented) return setError('Read and accept the accessibility disclosure first.');
    if (password !== confirmation) return setError('The passwords do not match.');

    setSaving(true);
    try {
      await createProtectionPassword(password);
      await setNativeProtectionEnabled(true);
      await setObservationMode(true);
      await setInstagramObservationMode(true);
      await openAccessibilitySettings();
      router.replace('/');
    } catch (setupError) {
      setError(setupError instanceof Error ? setupError.message : 'Setup could not be completed.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <SafeAreaView className="flex-1 bg-cream">
      <KeyboardAvoidingView className="flex-1" behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        <ScrollView contentContainerClassName="mx-auto w-full max-w-xl px-5 pb-12 pt-5" keyboardShouldPersistTaps="handled">
          <View className="h-12 w-12 items-center justify-center rounded-2xl bg-ink">
            <ShieldCheck color="#FCFBF7" size={24} />
          </View>
          <Text className="mt-6 text-3xl font-semibold tracking-tight text-ink">Set up protection</Text>
          <Text className="mt-2 text-base leading-6 text-moss">
            Zen Mode watches YouTube for Shorts and Instagram for Reels, Home-feed limits, and Explore. Messages and intentional content stay available.
          </Text>

          <View className="mt-7 rounded-3xl border border-clay/40 bg-paper p-5">
            <View className="flex-row items-center">
              <Eye color="#D88568" size={21} />
              <Text className="ml-3 text-base font-semibold text-ink">Accessibility access disclosure</Text>
            </View>
            <Text className="mt-4 leading-6 text-moss">
              To detect YouTube Shorts and Instagram feed surfaces, Zen Mode reads the visible accessibility structure of those two apps. It uses that information only on this device to recognize guarded screens.
            </Text>
            <Text className="mt-3 leading-6 text-moss">
              Zen Mode does not read other apps, collect typed text, store page contents, send screen contents, or use this access for analytics.
            </Text>
            <Pressable
              accessibilityRole="checkbox"
              accessibilityState={{ checked: consented }}
              className="mt-5 flex-row items-center"
              onPress={() => setConsented((value) => !value)}>
              <View className={`h-6 w-6 items-center justify-center rounded-lg border ${consented ? 'border-moss bg-moss' : 'border-sage bg-paper'}`}>
                {consented && <Check color="#FCFBF7" size={15} strokeWidth={3} />}
              </View>
              <Text className="ml-3 flex-1 text-sm font-medium leading-5 text-ink">I understand and consent to this specific use.</Text>
            </Pressable>
          </View>

          <View className="mt-5 rounded-3xl border border-mist bg-paper p-5">
            <View className="flex-row items-center">
              <LockKeyhole color="#436753" size={21} />
              <Text className="ml-3 text-base font-semibold text-ink">Protection password</Text>
            </View>
            <Text className="mt-2 text-sm leading-5 text-moss">At least 8 characters. It is required to weaken protection inside Zen Mode.</Text>
            <TextInput
              accessibilityLabel="Protection password"
              autoCapitalize="none"
              className="mt-4 rounded-2xl border border-mist bg-cream px-4 py-3.5 text-base text-ink"
              onChangeText={setPassword}
              placeholder="Create password"
              placeholderTextColor="#8FA997"
              secureTextEntry
              value={password}
            />
            <TextInput
              accessibilityLabel="Confirm protection password"
              autoCapitalize="none"
              className="mt-3 rounded-2xl border border-mist bg-cream px-4 py-3.5 text-base text-ink"
              onChangeText={setConfirmation}
              placeholder="Confirm password"
              placeholderTextColor="#8FA997"
              secureTextEntry
              value={confirmation}
            />
          </View>

          {error ? <Text className="mt-4 text-sm font-medium text-clay">{error}</Text> : null}
          <Pressable
            className={`mt-6 items-center rounded-2xl py-4 ${saving ? 'bg-sage' : 'bg-ink active:opacity-80'}`}
            disabled={saving}
            onPress={finishSetup}>
            <Text className="text-base font-semibold text-paper">{saving ? 'Securing…' : 'Create password & open settings'}</Text>
          </Pressable>
          <Text className="mt-4 text-center text-xs leading-5 text-sage">
            This version cannot prevent you from disabling the service in Android Settings or uninstalling the app.
          </Text>
        </ScrollView>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}
