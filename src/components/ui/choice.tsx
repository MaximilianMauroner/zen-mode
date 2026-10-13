import { Pressable, Text, View } from 'react-native';

// Radios report both `checked` (what TalkBack announces for a radio) and
// `selected` (what the Android integration script reads from the UI dump).

type ChipOption<T> = { value: T; label: string };

/**
 * Single-choice row of compact options, such as durations. `fill` stretches the
 * options to share the row; otherwise they wrap at their natural width.
 */
export function ChipGroup<T extends string | number | boolean>({
  label,
  options,
  selected,
  disabled = false,
  fill = false,
  onSelect,
}: {
  label?: string;
  options: readonly ChipOption<T>[];
  selected: T | undefined;
  disabled?: boolean;
  fill?: boolean;
  onSelect: (value: T) => void;
}) {
  return (
    <View accessibilityRole="radiogroup" accessibilityLabel={label} className="flex-row flex-wrap gap-2">
      {options.map((option) => {
        const active = option.value === selected;
        return (
          <Pressable
            key={String(option.value)}
            accessibilityRole="radio"
            accessibilityState={{ checked: active, selected: active, disabled }}
            disabled={disabled}
            onPress={() => onSelect(option.value)}
            className={`min-h-[48px] min-w-[56px] items-center justify-center rounded-2xl border px-3 ${fill ? 'flex-1' : ''} ${active ? 'border-accent bg-accentBg' : 'border-line2 bg-panel2'} ${disabled ? 'opacity-40' : 'active:opacity-70'}`}>
            <Text className={`text-center text-body font-semibold ${active ? 'text-accent' : 'text-copy'}`}>{option.label}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}

/** One option in a radio list where each choice needs a sentence of explanation. */
export function ChoiceCard({ title, detail, selected, disabled = false, onPress }: { title: string; detail: string; selected: boolean; disabled?: boolean; onPress: () => void }) {
  return (
    <Pressable
      accessibilityRole="radio"
      accessibilityState={{ checked: selected, selected, disabled }}
      disabled={disabled}
      onPress={onPress}
      className={`min-h-[48px] flex-row items-start gap-3 rounded-2xl border p-3.5 ${selected ? 'border-accent bg-accentBg' : 'border-line2 bg-panel2'} ${disabled ? 'opacity-40' : 'active:opacity-70'}`}>
      <View className={`mt-0.5 h-5 w-5 items-center justify-center rounded-full border-2 ${selected ? 'border-accent' : 'border-muted'}`}>
        {selected ? <View className="h-2.5 w-2.5 rounded-full bg-accent" /> : null}
      </View>
      <View className="flex-1">
        <Text className="text-item font-semibold text-copy">{title}</Text>
        <Text className="mt-0.5 text-support text-muted">{detail}</Text>
      </View>
    </Pressable>
  );
}
