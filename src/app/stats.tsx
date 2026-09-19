import { useCallback, useState } from 'react';
import { RefreshControl, Text, View } from 'react-native';
import { router, useFocusEffect } from 'expo-router';

import { Card, Row, RowGroup } from '@/components/ui/card';
import { ErrorNote, Screen, ScreenHeader, ScreenTitle } from '@/components/ui/screen';
import { getEnforcementStats, type EnforcementStats } from '@/features/protection/native';
import { getStatsScreenModel } from '@/features/protection/stats-screen-model';
import { colors } from '@/theme/colors';

export default function StatsScreen() {
  const [stats, setStats] = useState<EnforcementStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
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

  const screenModel = stats ? getStatsScreenModel(stats) : null;

  return (
    <Screen refreshControl={<RefreshControl refreshing={loading} onRefresh={() => void load()} tintColor={colors.accent} />}>
      <ScreenHeader label="STATISTICS" onBack={() => router.back()} backDisabled={loading} />
      <ScreenTitle title="Interventions" description="Local totals show when a protection rule actually stopped or covered something. Detector observations are not counted." />

      <Card emphasis>
        <Text className="text-[12px] font-bold tracking-[0.16em] text-accent">ALL TIME</Text>
        <Text className="mt-2 text-[44px] font-semibold leading-[50px] text-copy">{screenModel?.total ?? '—'}</Text>
        <Text className="mt-1 text-[15px] text-muted">total interventions</Text>
      </Card>

      <ErrorNote message={error} />
      <View className="gap-3">
        <Text accessibilityRole="header" className="text-[18px] font-semibold text-copy">By protection</Text>
        {screenModel ? (
          <RowGroup>
            {screenModel.rows.map(({ key, label, detail, value }) => (
              <Row key={key} label={label} detail={detail} value={value} />
            ))}
            {screenModel.other ? <Row {...screenModel.other} /> : null}
          </RowGroup>
        ) : (
          <Card><Text className="text-[14px] text-muted">{loading ? 'Loading statistics…' : 'No statistics available.'}</Text></Card>
        )}
      </View>

      <Text className="text-[12px] leading-[18px] text-faint">Statistics stay on this device and contain only aggregate counts, fixed categories, and the latest event time. Clearing Zen Mode app data resets them.</Text>
    </Screen>
  );
}
