import { router, useLocalSearchParams } from 'expo-router';
import { Pressable, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

export default function EndScreen() {
  const { appName, mode } = useLocalSearchParams<{ appName?: string; mode?: string }>();
  const kids = mode === 'kids';
  return (
    <SafeAreaView className={`flex-1 ${kids ? 'bg-accent' : 'bg-night'}`}>
      <View className="flex-1 items-center justify-center gap-5 px-6">
        <Text accessibilityRole="header" className={`text-center text-display font-semibold ${kids ? 'text-onAccent' : 'text-copy'}`}>
          {kids ? "Time's up.\nGo play." : 'Daily limit reached'}
        </Text>
        <Text className={`text-center text-title ${kids ? 'text-onAccent' : 'text-muted'}`}>{appName ?? 'This app'}</Text>
        <Pressable accessibilityRole="button" onPress={() => router.back()} className={`min-h-[48px] w-full items-center justify-center rounded-2xl ${kids ? 'bg-panel2' : 'bg-accent'}`}>
          <Text className={`text-item font-semibold ${kids ? 'text-copy' : 'text-onAccent'}`}>Back home</Text>
        </Pressable>
      </View>
    </SafeAreaView>
  );
}
