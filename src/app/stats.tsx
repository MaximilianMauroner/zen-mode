import { Platform, RefreshControl, Text, View } from 'react-native';
import { router } from 'expo-router';

import { Card, Row, RowGroup } from '@/components/ui/card';
import { GuardNotes } from '@/components/ui/guard-notes';
import { Screen, ScreenHeader } from '@/components/ui/screen';
import { IDLE_ACTION, isReading, readValue } from '@/features/protection/control-state';
import { getEnforcementStats, type EnforcementStats } from '@/features/protection/native';
import type { ReadResult } from '@/features/protection/protection-reads';
import { getStatsScreenState } from '@/features/protection/stats-screen-model';
import { useScreenRead } from '@/features/protection/use-protection-read';
import { colors } from '@/theme/colors';

async function readStats(): Promise<ReadResult<EnforcementStats>> {
  if (Platform.OS === 'web') return { kind: 'android-required' };
  return { kind: 'ready', value: await getEnforcementStats() };
}

export default function StatsScreen() {
  const { read, refresh } = useScreenRead(readStats, 'Statistics could not be read. Try again.');
  const loading = isReading(read);
  const screenState = getStatsScreenState(Platform.OS, readValue(read), loading);
  const screenModel = screenState.model;

  return (
    <Screen refreshControl={screenState.available ? <RefreshControl refreshing={loading} onRefresh={() => void refresh()} tintColor={colors.accent} /> : undefined}>
      <ScreenHeader title="Statistics" onBack={() => router.back()} backDisabled={loading} />
      <Text className="-mt-1 text-body text-muted">Local totals show when a protection rule actually stopped or covered something. Detector observations are not counted.</Text>

      <Card emphasis>
        <Text className="text-support font-semibold text-accent">All time</Text>
        <Text className="mt-1 text-metric font-semibold tracking-tight text-copy" style={{ fontVariant: ['tabular-nums'] }}>{screenModel?.total ?? '—'}</Text>
        <Text className="text-item text-muted">total interventions</Text>
      </Card>

      <GuardNotes reads={[read]} action={IDLE_ACTION} />
      <View className="gap-3">
        <Text accessibilityRole="header" className="text-title font-semibold text-copy">By protection</Text>
        {screenModel ? (
          <RowGroup>
            {screenModel.rows.map(({ key, label, detail, value }) => (
              <Row key={key} label={label} detail={detail} value={value} />
            ))}
            {screenModel.other ? <Row {...screenModel.other} /> : null}
          </RowGroup>
        ) : (
          <Card><Text className="text-body text-muted">{screenState.message}</Text></Card>
        )}
      </View>

      <Text className="text-caption text-faint">Statistics stay on this device and contain only aggregate counts, fixed categories, and the latest event time. Clearing Zen Mode app data resets them.</Text>
    </Screen>
  );
}
