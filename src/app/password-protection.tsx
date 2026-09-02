import { enablePasswordProtection, isPasswordProtectionEnabled } from '@/features/protection/credential';
import { ArrowLeft, LockKeyhole } from 'lucide-react-native';
import { router, useFocusEffect } from 'expo-router';
import { useCallback, useRef, useState } from 'react';
import { KeyboardAvoidingView, Platform, Pressable, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

export default function PasswordProtectionScreen() {
  const [enabled, setEnabled] = useState<boolean | null>(null);
  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [navigating, setNavigating] = useState(false);
  const savingRef = useRef(false);
  const navigationRef = useRef(false);
  const refreshInFlightRef = useRef(false);

  const refresh = useCallback(async () => {
    if (refreshInFlightRef.current) return;
    refreshInFlightRef.current = true;
    setLoading(true);
    setEnabled(null);
    setError('');
    try {
      setEnabled(await isPasswordProtectionEnabled());
    } catch {
      setError('Password protection status could not be read. Try again.');
    } finally {
      refreshInFlightRef.current = false;
      setLoading(false);
      navigationRef.current = false;
      setNavigating(false);
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      void refresh();
    }, [refresh]),
  );

  const enable = () => {
    if (loading || enabled !== false || savingRef.current) return;
    setError('');
    if (password !== confirmation) {
      setError('The passwords do not match.');
      return;
    }
    savingRef.current = true;
    setSaving(true);
    void (async () => {
      try {
        await enablePasswordProtection(password);
        setPassword('');
        setConfirmation('');
        await refresh();
      } catch (cause) {
        setError(cause instanceof Error ? cause.message : 'Password protection could not be enabled.');
      } finally {
        savingRef.current = false;
        setSaving(false);
      }
    })();
  };

  const navigateToDisable = () => {
    if (loading || enabled !== true || navigationRef.current) return;
    navigationRef.current = true;
    setNavigating(true);
    try {
      router.push('/unlock?intent=password-disable');
    } catch (cause) {
      navigationRef.current = false;
      setNavigating(false);
      setError(cause instanceof Error ? cause.message : 'Password settings could not be opened.');
    }
  };

  const goBack = () => {
    if (navigationRef.current) return;
    navigationRef.current = true;
    setNavigating(true);
    try {
      router.back();
    } catch (cause) {
      navigationRef.current = false;
      setNavigating(false);
      setError(cause instanceof Error ? cause.message : 'Could not leave password settings.');
    }
  };

  return (
    <SafeAreaView className="flex-1 bg-night">
      <KeyboardAvoidingView className="flex-1 px-4" behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        <View className="mx-auto w-full max-w-xl flex-1 justify-center pb-20">
          <Pressable accessibilityLabel="Go back" className="mb-8 h-10 w-10 items-center justify-center rounded-xl border border-line bg-panel2" disabled={navigating || saving} onPress={goBack}>
            <ArrowLeft color="#A7E782" size={19} />
          </Pressable>
          <View className="h-10 w-10 items-center justify-center rounded-xl border border-line bg-panel2">
            <LockKeyhole color="#A7E782" size={21} />
          </View>
          <Text className="mt-7 text-4xl font-semibold tracking-tight text-copy">Password protection</Text>
          <Text className="mt-3 text-base leading-6 text-muted">
            {enabled === null ? 'Status unavailable. Try again before changing this setting.' : enabled ? 'Protected changes require your password.' : 'Off by default. Settings can be changed without a password.'}
          </Text>

          {enabled === null ? (
            <Pressable className="mt-7 items-center rounded-2xl bg-accent py-3.5" disabled={loading} onPress={() => void refresh()}>
              <Text className="text-base font-bold text-onAccent">{loading ? 'Checking…' : 'Retry'}</Text>
            </Pressable>
          ) : enabled ? (
            <Pressable className="mt-7 items-center rounded-2xl border border-dangerLine bg-dangerBg py-3.5" disabled={navigating} onPress={navigateToDisable}>
              <Text className="text-base font-bold text-danger">{navigating ? 'Opening…' : 'Disable password protection'}</Text>
            </Pressable>
          ) : (
            <>
              <TextInput accessibilityLabel="New protection password" autoCapitalize="none" className="mt-7 rounded-2xl border border-line bg-panel px-4 py-4 text-base text-copy" editable={!saving} onChangeText={setPassword} placeholder="Create password" placeholderTextColor="#94A89A" secureTextEntry value={password} />
              <TextInput accessibilityLabel="Confirm protection password" autoCapitalize="none" className="mt-3 rounded-2xl border border-line bg-panel px-4 py-4 text-base text-copy" editable={!saving} onChangeText={setConfirmation} onSubmitEditing={enable} placeholder="Confirm password" placeholderTextColor="#94A89A" secureTextEntry value={confirmation} />
              <Pressable className="mt-5 items-center rounded-2xl bg-accent py-3.5" disabled={saving} onPress={enable}>
                <Text className="text-base font-bold text-onAccent">{saving ? 'Enabling…' : 'Enable password protection'}</Text>
              </Pressable>
            </>
          )}
          {error ? <Text className="mt-4 text-sm font-medium text-danger">{error}</Text> : null}
        </View>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}
