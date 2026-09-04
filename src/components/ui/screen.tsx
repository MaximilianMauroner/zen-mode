import { type ComponentType, type ReactNode } from 'react';
import { KeyboardAvoidingView, Platform, Pressable, ScrollView, Text, TextInput, View, type ScrollViewProps } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { ArrowLeft, type LucideProps } from 'lucide-react-native';

import { StatusPill, type PillTone } from '@/components/ui/pill';
import { colors } from '@/theme/colors';

/** Scrolling page frame. Every screen shares the same width, gutter, and rhythm. */
export function Screen({ children, refreshControl }: { children: ReactNode; refreshControl?: ScrollViewProps['refreshControl'] }) {
  return (
    <SafeAreaView className="flex-1 bg-night" edges={['top']}>
      <ScrollView contentContainerClassName="mx-auto w-full max-w-xl gap-5 px-5 pb-14 pt-3" refreshControl={refreshControl}>
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
      className={`${tile} ${disabled ? 'opacity-40' : 'active:opacity-60'}`}
      disabled={disabled}
      onPress={onPress}>
      <Icon color={colors.accent} size={19} />
    </Pressable>
  );
}

/**
 * Top bar: optional back button, an accent wordmark, and at most one solid pill.
 * The solid pill is the single loudest element on a screen, so no other pill
 * should use the filled accent treatment.
 */
export function ScreenHeader({
  icon: Icon,
  label,
  pill,
  onBack,
  backDisabled,
}: {
  /** Wordmark glyph. Ignored when `onBack` is set, since the back tile takes that slot. */
  icon?: ComponentType<LucideProps>;
  label: string;
  pill?: { label: string; tone?: PillTone; solid?: boolean };
  onBack?: () => void;
  backDisabled?: boolean;
}) {
  return (
    <View className="flex-row items-center justify-between">
      <View className="flex-row items-center">
        {onBack ? (
          <View className="mr-3">
            <IconTile icon={ArrowLeft} accessibilityLabel="Go back" disabled={backDisabled} onPress={onBack} />
          </View>
        ) : Icon ? (
          <View className="mr-2">
            <Icon color={colors.accent} size={17} />
          </View>
        ) : null}
        <Text className="text-[13px] font-bold tracking-[0.18em] text-accent">{label}</Text>
      </View>
      {pill ? <StatusPill label={pill.label} tone={pill.tone} solid={pill.solid} /> : null}
    </View>
  );
}

/** Page title block. `highlight` renders a trailing accent line under the title. */
export function ScreenTitle({ title, highlight, description }: { title: string; highlight?: string; description?: string }) {
  return (
    <View>
      <Text className="text-[34px] font-semibold leading-[40px] tracking-tight text-copy">
        {title}
        {highlight ? <Text className="text-accent">{`\n${highlight}`}</Text> : null}
      </Text>
      {description ? <Text className="mt-2.5 text-[15px] leading-[22px] text-muted">{description}</Text> : null}
    </View>
  );
}

/** Inline error line. Kept at the foot of a screen so it never shifts the layout above it. */
export function ErrorNote({ message }: { message: string }) {
  if (!message) return null;
  return (
    <View className="rounded-2xl border border-dangerLine bg-dangerBg px-4 py-3">
      <Text className="text-[13px] font-medium leading-[18px] text-danger">{message}</Text>
    </View>
  );
}

/** Centered, keyboard-aware frame for the short credential forms. */
export function FormScreen({ children, onBack, backDisabled }: { children: ReactNode; onBack: () => void; backDisabled?: boolean }) {
  return (
    <SafeAreaView className="flex-1 bg-night" edges={['top']}>
      <KeyboardAvoidingView className="flex-1 px-5" behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        <View className="mx-auto w-full max-w-xl pt-3">
          <IconTile icon={ArrowLeft} accessibilityLabel="Go back" disabled={backDisabled} onPress={onBack} />
        </View>
        <View className="mx-auto w-full max-w-xl flex-1 justify-center gap-4 pb-24">{children}</View>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

/** Secure text field shared by the credential forms. */
export function PasswordField({
  label,
  placeholder,
  value,
  editable,
  onChangeText,
  onSubmitEditing,
  autoFocus,
}: {
  label: string;
  placeholder: string;
  value: string;
  editable: boolean;
  onChangeText: (next: string) => void;
  onSubmitEditing?: () => void;
  autoFocus?: boolean;
}) {
  return (
    <TextInput
      accessibilityLabel={label}
      autoCapitalize="none"
      autoFocus={autoFocus}
      className="rounded-2xl border border-line bg-panel px-4 py-4 text-[16px] text-copy"
      editable={editable}
      onChangeText={onChangeText}
      onSubmitEditing={onSubmitEditing}
      placeholder={placeholder}
      placeholderTextColor={colors.faint}
      secureTextEntry
      value={value}
    />
  );
}
