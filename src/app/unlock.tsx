import { verifyProtectionPassword } from '@/features/protection/credential';
import { getZenGuardStatus, setNativeProtectionEnabled, setObservationMode } from '@/features/protection/native';
import { ArrowLeft, LockKeyhole } from 'lucide-react-native';
import { router, useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { KeyboardAvoidingView, Platform, Pressable, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

type UnlockIntent = 'disable' | 'enforce';

export default function UnlockScreen() {
  const { intent: requestedIntent } = useLocalSearchParams<{ intent?: string }>();
  const intent: UnlockIntent = requestedIntent === 'enforce' ? 'enforce' : 'disable';
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [checking, setChecking] = useState(false);

  const confirm = async () => {
    setChecking(true);
    setError('');
    try {
      if (!(await verifyProtectionPassword(password))) {
        setError('That password is not correct. Protection was not changed.');
        return;
      }

      if (intent === 'enforce') {
        const status = await getZenGuardStatus();
        if (!status.lastDetectionAt) {
          setError('Open YouTube Shorts once in observation mode before enabling enforcement.');
          return;
        }
        await setObservationMode(false);
      } else {
        await setNativeProtectionEnabled(false);
      }
      router.replace('/');
    } catch {
      setError('Protection could not be changed. Try again.');
    } finally {
      setChecking(false);
    }
  };

  return (
    <SafeAreaView className="flex-1 bg-cream">
      <KeyboardAvoidingView className="flex-1 px-5" behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        <View className="mx-auto w-full max-w-xl flex-1 justify-center pb-20">
          <Pressable accessibilityLabel="Go back" className="mb-8 h-11 w-11 items-center justify-center rounded-full bg-paper" onPress={() => router.back()}>
            <ArrowLeft color="#436753" size={20} />
          </Pressable>
          <View className="h-12 w-12 items-center justify-center rounded-2xl bg-ink">
            <LockKeyhole color="#FCFBF7" size={23} />
          </View>
          <Text className="mt-6 text-3xl font-semibold tracking-tight text-ink">
            {intent === 'enforce' ? 'Activate enforcement' : 'Disable protection'}
          </Text>
          <Text className="mt-2 text-base leading-6 text-moss">
            Enter the password you created during setup. An incorrect password leaves protection unchanged.
          </Text>
          <TextInput
            accessibilityLabel="Protection password"
            autoFocus
            className="mt-7 rounded-2xl border border-mist bg-paper px-4 py-4 text-base text-ink"
            onChangeText={setPassword}
            onSubmitEditing={confirm}
            placeholder="Password"
            placeholderTextColor="#8FA997"
            secureTextEntry
            value={password}
          />
          {error ? <Text className="mt-4 text-sm font-medium leading-5 text-clay">{error}</Text> : null}
          <Pressable className="mt-5 items-center rounded-2xl bg-ink py-4 active:opacity-80" disabled={checking} onPress={confirm}>
            <Text className="text-base font-semibold text-paper">{checking ? 'Checking…' : 'Confirm'}</Text>
          </Pressable>
        </View>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}
