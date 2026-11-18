import '@/global.css';

import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';

import { GuardStatusProvider } from '@/features/protection/guard-status-context';
import { diagnosticsAvailable } from '@/features/protection/native';
import { colors } from '@/theme/colors';

export default function RootLayout() {
  return (
    <GuardStatusProvider>
      <StatusBar style="light" />
      <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: colors.night } }}>
        <Stack.Protected guard={diagnosticsAvailable}>
          <Stack.Screen name="diagnostics" />
        </Stack.Protected>
      </Stack>
    </GuardStatusProvider>
  );
}
