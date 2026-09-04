import { Pressable, Text } from 'react-native';

type ButtonProps = { title: string; disabled?: boolean; onPress: () => void; className?: string };

/** The single accent call to action on a screen. */
export function PrimaryButton({ title, disabled, onPress, className = '' }: ButtonProps) {
  return (
    <Pressable
      accessibilityRole="button"
      className={`items-center rounded-2xl bg-accent py-3.5 ${disabled ? 'opacity-40' : 'active:opacity-80'} ${className}`}
      disabled={disabled}
      onPress={onPress}>
      <Text className="text-base font-bold text-onAccent">{title}</Text>
    </Pressable>
  );
}

/** Bordered action for anything that should not compete with the primary button. */
export function SecondaryButton({ title, disabled, onPress, className = '' }: ButtonProps) {
  return (
    <Pressable
      accessibilityRole="button"
      className={`items-center rounded-2xl border border-line bg-panel2 py-3.5 ${disabled ? 'opacity-40' : 'active:opacity-80'} ${className}`}
      disabled={disabled}
      onPress={onPress}>
      <Text className="text-base font-semibold text-copy">{title}</Text>
    </Pressable>
  );
}

/** Destructive action. Reads as a warning without shouting. */
export function DangerButton({ title, disabled, onPress, className = '' }: ButtonProps) {
  return (
    <Pressable
      accessibilityRole="button"
      className={`items-center rounded-2xl border border-dangerLine bg-dangerBg py-3.5 ${disabled ? 'opacity-40' : 'active:opacity-80'} ${className}`}
      disabled={disabled}
      onPress={onPress}>
      <Text className="text-base font-semibold text-danger">{title}</Text>
    </Pressable>
  );
}
