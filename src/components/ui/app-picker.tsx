import { useCallback, useMemo, useRef, useState } from 'react';
import { FlatList, KeyboardAvoidingView, Modal, Platform, Pressable, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useReducedMotion } from 'react-native-reanimated';
import { getInstalledApps, type InstalledApp } from '@/features/protection/native';
import { colors } from '@/theme/colors';
import { Plus } from 'lucide-react-native';
import { SheetHeader } from './screen';

type AppPickerProps = {
  visible: boolean;
  configuredPackages: ReadonlySet<string>;
  onClose: () => void;
  onSelect: (app: InstalledApp) => void;
};

/** Open the native drawer first; cache inventory and virtualize the full app list inside it. */
export function AppPicker({ visible, configuredPackages, onClose, onSelect }: AppPickerProps) {
  const [apps, setApps] = useState<InstalledApp[] | null>(null);
  const [query, setQuery] = useState('');
  const [error, setError] = useState('');
  const inFlight = useRef(false);
  const reduceMotion = useReducedMotion();
  const loadApps = useCallback(async () => {
    if (inFlight.current) return;
    inFlight.current = true;
    setError('');
    try { setApps(await getInstalledApps()); }
    catch { setError('Could not load apps. Try again.'); }
    finally { inFlight.current = false; }
  }, []);
  const filtered = useMemo(() => (apps ?? []).filter((app) => !configuredPackages.has(app.packageName) && app.label.toLowerCase().includes(query.trim().toLowerCase())), [apps, configuredPackages, query]);

  return (
    <Modal visible={visible} transparent animationType={reduceMotion ? 'none' : 'slide'} onRequestClose={onClose} onShow={() => { void loadApps(); }} statusBarTranslucent>
      <View className="flex-1 justify-end bg-black/50">
        <Pressable accessibilityRole="button" accessibilityLabel="Close app picker" onPress={onClose} className="absolute inset-0" />
        <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={{ height: '80%' }}>
          <SafeAreaView edges={['bottom']} className="flex-1 rounded-t-3xl border-t border-line2 bg-panel pt-3">
            <SheetHeader title="Add app" closeLabel="Close app picker" onClose={onClose} />
            <View className="flex-1 px-5">
              <TextInput accessibilityLabel="Search apps" placeholder="Search apps" placeholderTextColor={colors.faint} value={query} onChangeText={setQuery} autoCorrect={false} autoCapitalize="none" className="mb-2 min-h-[48px] rounded-2xl border border-line2 bg-night px-4 py-3 text-item text-copy" />
              {error ? <Pressable accessibilityRole="button" onPress={() => { void loadApps(); }} className="min-h-[48px] min-w-[48px] justify-center"><Text className="text-support font-medium text-danger">{error}</Text></Pressable> : null}
              <FlatList
                data={visible ? filtered : []}
                keyExtractor={(app) => app.packageName}
                keyboardShouldPersistTaps="handled"
                initialNumToRender={12}
                maxToRenderPerBatch={12}
                windowSize={5}
                getItemLayout={(_, index) => ({ length: 64, offset: 64 * index, index })}
                renderItem={({ item }) => (
                  <Pressable accessibilityRole="button" accessibilityLabel={`Add ${item.label}`} onPress={() => { setQuery(''); onSelect(item); }} style={{ height: 64 }} className="flex-row items-center justify-between border-b border-line active:opacity-60">
                    <Text numberOfLines={1} className="flex-1 pr-3 text-item font-medium text-copy">{item.label}</Text><Plus color={colors.accent} size={20} />
                  </Pressable>
                )}
                ListEmptyComponent={<Text className="py-5 text-support text-muted">{apps === null ? error ? '' : 'Loading apps…' : 'No apps match.'}</Text>}
              />
            </View>
          </SafeAreaView>
        </KeyboardAvoidingView>
      </View>
    </Modal>
  );
}
