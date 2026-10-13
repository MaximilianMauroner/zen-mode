import { type ComponentType, type ReactNode } from 'react';
import { Pressable, ScrollView, Text, View, type ScrollViewProps } from 'react-native';
import { SafeAreaView, type Edge } from 'react-native-safe-area-context';
import { ArrowLeft, X, type LucideProps } from 'lucide-react-native';

import { colors } from '@/theme/colors';
import { metrics } from '@/theme/metrics';

/** Scrolling page frame. Every screen shares the same width, gutter, and rhythm. */
export function Screen({ children, refreshControl, edges = ['top'] }: { children: ReactNode; refreshControl?: ScrollViewProps['refreshControl']; edges?: Edge[] }) {
  return (
    <SafeAreaView className="flex-1 bg-night" edges={edges}>
      <ScrollView keyboardShouldPersistTaps="handled" contentContainerClassName="mx-auto w-full max-w-xl gap-5 px-5 pb-14 pt-3" refreshControl={refreshControl}>
        {children}
      </ScrollView>
    </SafeAreaView>
  );
}

/** Square icon tile used for back buttons and screen glyphs. */
export function IconTile({ icon: Icon, onPress, disabled, accessibilityLabel }: { icon: ComponentType<LucideProps>; onPress?: () => void; disabled?: boolean; accessibilityLabel?: string }) {
  const tile = 'h-10 w-10 items-center justify-center rounded-2xl border border-line bg-panel2';
  if (!onPress) {
    return (
      <View className={tile}>
        <Icon color={colors.accent} size={19} />
      </View>
    );
  }
  return (
    <Pressable
      accessibilityLabel={accessibilityLabel}
      accessibilityRole="button"
      accessibilityState={{ disabled: Boolean(disabled) }}
      className={`items-center justify-center ${disabled ? 'opacity-40' : 'active:opacity-60'}`}
      disabled={disabled}
      onPress={onPress}
      style={{ minHeight: metrics.touchTarget, minWidth: metrics.touchTarget }}>
      <View className={tile}>
        <Icon color={colors.accent} size={19} />
      </View>
    </Pressable>
  );
}

/** Top bar for pushed screens: a back button and the screen's title. */
export function ScreenHeader({ title, onBack, backDisabled }: { title: string; onBack: () => void; backDisabled?: boolean }) {
  return (
    <View className="-ml-1 flex-row items-center gap-2">
      <IconTile icon={ArrowLeft} accessibilityLabel="Go back" disabled={backDisabled} onPress={onBack} />
      <Text accessibilityRole="header" numberOfLines={1} className="flex-1 text-headline font-semibold text-copy">{title}</Text>
    </View>
  );
}

/** Page title block for first-run and summary screens. */
export function ScreenTitle({ title, description }: { title: string; description?: string }) {
  return (
    <View>
      <Text accessibilityRole="header" className="text-display font-semibold tracking-tight text-copy">{title}</Text>
      {description ? <Text className="mt-2.5 text-body text-muted">{description}</Text> : null}
    </View>
  );
}

/** Title row of a bottom sheet. The close button keeps a full Android touch target. */
export function SheetHeader({ title, closeLabel, disabled, onClose }: { title: string; closeLabel: string; disabled?: boolean; onClose: () => void }) {
  return (
    <View className="mb-3 flex-row items-center justify-between px-5">
      <Text accessibilityRole="header" className="text-headline font-semibold text-copy">{title}</Text>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={closeLabel}
        accessibilityState={{ disabled: Boolean(disabled) }}
        disabled={disabled}
        onPress={onClose}
        style={{ minHeight: metrics.touchTarget, minWidth: metrics.touchTarget }}
        className={`-mr-3 items-center justify-center ${disabled ? 'opacity-40' : 'active:opacity-60'}`}>
        <X color={colors.muted} size={22} />
      </Pressable>
    </View>
  );
}

/** Inline error line. Kept at the foot of a screen so it never shifts the layout above it. */
export function ErrorNote({ message }: { message: string }) {
  if (!message) return null;
  return (
    <View className="rounded-2xl border border-dangerLine bg-dangerBg px-4 py-3">
      <Text accessibilityLiveRegion="polite" className="text-support font-medium text-danger">{message}</Text>
    </View>
  );
}
