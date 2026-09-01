import { Bell, ChevronRight, Clock3, Flame, LockKeyhole, MoreHorizontal, Smartphone } from 'lucide-react-native';
import { useState } from 'react';
import { Pressable, ScrollView, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

const weeklyUsage = [
  { day: 'M', value: 36 },
  { day: 'T', value: 52 },
  { day: 'W', value: 45 },
  { day: 'T', value: 68 },
  { day: 'F', value: 40 },
  { day: 'S', value: 26 },
  { day: 'S', value: 18 },
];

const distractions = [
  { name: 'Instagram', detail: '34 min · 12 pickups', color: '#D88568', initial: 'I' },
  { name: 'YouTube', detail: '26 min · 5 pickups', color: '#D65F58', initial: 'Y' },
  { name: 'Safari', detail: '18 min · 9 pickups', color: '#5D8FB8', initial: 'S' },
];

export default function HomeScreen() {
  const [focusActive, setFocusActive] = useState(false);

  return (
    <SafeAreaView className="flex-1 bg-cream" edges={['top']}>
      <ScrollView
        className="flex-1"
        contentContainerClassName="mx-auto w-full max-w-xl px-5 pb-12"
        showsVerticalScrollIndicator={false}>
        <View className="flex-row items-center justify-between pb-6 pt-4">
          <View>
            <Text className="text-sm font-medium tracking-wide text-moss">TUESDAY, SEPTEMBER 1</Text>
            <Text className="mt-1 text-3xl font-semibold tracking-tight text-ink">Good morning, Max.</Text>
          </View>
          <Pressable
            accessibilityLabel="Notifications"
            className="h-11 w-11 items-center justify-center rounded-full border border-mist bg-paper active:opacity-70">
            <Bell color="#436753" size={20} strokeWidth={1.8} />
            <View className="absolute right-3 top-3 h-2 w-2 rounded-full border border-paper bg-clay" />
          </Pressable>
        </View>

        <View className="overflow-hidden rounded-[30px] bg-ink px-6 py-6">
          <View className="flex-row items-start justify-between">
            <View>
              <Text className="text-sm font-medium text-sage">TODAY’S SCREEN TIME</Text>
              <View className="mt-2 flex-row items-end">
                <Text className="text-5xl font-semibold tracking-tighter text-paper">1h 42m</Text>
                <Text className="mb-1.5 ml-2 text-sm text-sage">of 2h 30m</Text>
              </View>
            </View>
            <View className="h-16 w-16 items-center justify-center rounded-full border-[6px] border-moss">
              <Text className="text-sm font-semibold text-paper">68%</Text>
            </View>
          </View>

          <View className="my-5 h-px bg-moss/60" />
          <View className="flex-row">
            <View className="flex-1 flex-row items-center">
              <Smartphone color="#8FA997" size={18} strokeWidth={1.8} />
              <View className="ml-3">
                <Text className="text-xs text-sage">PICKUPS</Text>
                <Text className="mt-0.5 text-lg font-semibold text-paper">38</Text>
              </View>
            </View>
            <View className="w-px bg-moss/60" />
            <View className="flex-1 flex-row items-center pl-5">
              <Clock3 color="#8FA997" size={18} strokeWidth={1.8} />
              <View className="ml-3">
                <Text className="text-xs text-sage">FIRST PICKUP</Text>
                <Text className="mt-0.5 text-lg font-semibold text-paper">8:14</Text>
              </View>
            </View>
          </View>
        </View>

        <Pressable
          accessibilityRole="button"
          accessibilityState={{ selected: focusActive }}
          onPress={() => setFocusActive((active) => !active)}
          className={`mt-4 flex-row items-center rounded-2xl px-5 py-4 active:opacity-80 ${focusActive ? 'bg-moss' : 'border border-mist bg-paper'}`}>
          <View className={`h-11 w-11 items-center justify-center rounded-full ${focusActive ? 'bg-paper/15' : 'bg-mist'}`}>
            <LockKeyhole color={focusActive ? '#FCFBF7' : '#436753'} size={20} strokeWidth={1.8} />
          </View>
          <View className="ml-4 flex-1">
            <Text className={`text-base font-semibold ${focusActive ? 'text-paper' : 'text-ink'}`}>
              {focusActive ? 'Focus mode is on' : 'Start focus mode'}
            </Text>
            <Text className={`mt-0.5 text-sm ${focusActive ? 'text-mist' : 'text-moss'}`}>
              {focusActive ? 'Tap to end your session' : 'Block distracting apps for 45 min'}
            </Text>
          </View>
          <ChevronRight color={focusActive ? '#FCFBF7' : '#8FA997'} size={20} />
        </Pressable>

        <View className="mt-8 flex-row items-end justify-between">
          <View>
            <Text className="text-xl font-semibold tracking-tight text-ink">This week</Text>
            <Text className="mt-1 text-sm text-moss">Down 18% from last week</Text>
          </View>
          <View className="flex-row items-center rounded-full bg-mist px-3 py-1.5">
            <Flame color="#436753" size={14} />
            <Text className="ml-1.5 text-xs font-semibold text-moss">4 day streak</Text>
          </View>
        </View>

        <View className="mt-4 rounded-3xl border border-mist bg-paper px-5 pb-4 pt-5">
          <View className="h-32 flex-row items-end justify-between">
            {weeklyUsage.map((item, index) => (
              <View className="h-full flex-1 items-center justify-end" key={`${item.day}-${index}`}>
                <View
                  className={`w-5 rounded-full ${index === 3 ? 'bg-clay' : 'bg-sage'}`}
                  style={{ height: item.value }}
                />
                <Text className={`mt-3 text-xs ${index === 3 ? 'font-bold text-ink' : 'text-sage'}`}>{item.day}</Text>
              </View>
            ))}
          </View>
        </View>

        <View className="mt-8 flex-row items-center justify-between">
          <View>
            <Text className="text-xl font-semibold tracking-tight text-ink">Most distracting</Text>
            <Text className="mt-1 text-sm text-moss">Where your attention went today</Text>
          </View>
          <Pressable accessibilityLabel="More distraction options" className="p-2 active:opacity-60">
            <MoreHorizontal color="#436753" size={22} />
          </Pressable>
        </View>

        <View className="mt-4 overflow-hidden rounded-3xl border border-mist bg-paper px-5">
          {distractions.map((app, index) => (
            <View className={`flex-row items-center py-4 ${index ? 'border-t border-mist' : ''}`} key={app.name}>
              <View className="h-11 w-11 items-center justify-center rounded-2xl" style={{ backgroundColor: app.color }}>
                <Text className="text-base font-bold text-white">{app.initial}</Text>
              </View>
              <View className="ml-4 flex-1">
                <Text className="text-base font-semibold text-ink">{app.name}</Text>
                <Text className="mt-0.5 text-sm text-moss">{app.detail}</Text>
              </View>
              <ChevronRight color="#8FA997" size={18} />
            </View>
          ))}
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}
