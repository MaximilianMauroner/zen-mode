import { useCallback, useState } from 'react';
import { Platform, RefreshControl, Text, View } from 'react-native';
import { router, useFocusEffect } from 'expo-router';

import { Card, Row, RowGroup } from '@/components/ui/card';
import { ErrorNote, Screen, ScreenHeader } from '@/components/ui/screen';
import { getEnforcementStats, type EnforcementStats } from '@/features/protection/native';
import { getStatsScreenState } from '@/features/protection/stats-screen-model';
import { colors } from '@/theme/colors';

export default function StatsScreen() {
  const [stats, setStats] = useState<EnforcementStats | null>(null);
  const [loading, setLoading] = useState(Platform.OS !== 'web');
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    if (Platform.OS === 'web') return;
    setLoading(true);
    setError('');
    try {
      setStats(await getEnforcementStats());
    } catch {
      setStats(null);
      setError('Statistics could not be read. Try again.');
    } finally {
      setLoading(false);
    }
  }, []);

  useFocusEffect(useCallback(() => {
    void load();
  }, [load]));

  const screenState = getStatsScreenState(Platform.OS, stats, loading);
  const screenModel = screenState.model;

  return (
    <Screen refreshControl={screenState.available ? <RefreshControl refreshing={loading} onRefresh={() => void load()} tintColor={colors.accent} /> : undefined}>
      <ScreenHeader title="Statistics" onBack={() => router.back()} backDisabled={loading} />
      <Text className="-mt-1 text-body text-muted">Local totals show when a protection rule actually stopped or covered something. Detector observations are not counted.</Text>

      <Card emphasis>
        <Text className="text-support font-semibold text-accent">All time</Text>
        <Text className="mt-1 text-metric font-semibold tracking-tight text-copy" style={{ fontVariant: ['tabular-nums'] }}>{screenModel?.total ?? '—'}</Text>
        <Text className="text-item text-muted">total interventions</Text>
      </Card>

      <ErrorNote message={error} />
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
