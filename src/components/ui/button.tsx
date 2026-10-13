import { Pressable, Text } from 'react-native';

type ButtonProps = { title: string; disabled?: boolean; onPress: () => void; className?: string };

const TONE = {
  primary: { surface: 'border-accent bg-accent', text: 'font-bold text-onAccent' },
  secondary: { surface: 'border-line2 bg-panel2', text: 'font-semibold text-copy' },
  danger: { surface: 'border-dangerLine bg-dangerBg', text: 'font-semibold text-danger' },
} as const;

function Button({ title, disabled, onPress, className = '', tone }: ButtonProps & { tone: keyof typeof TONE }) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled: Boolean(disabled) }}
      className={`min-h-[48px] min-w-[48px] items-center justify-center rounded-2xl border px-4 py-3 ${TONE[tone].surface} ${disabled ? 'opacity-40' : 'active:opacity-80'} ${className}`}
      disabled={disabled}
      onPress={onPress}>
      <Text className={`text-center text-item ${TONE[tone].text}`}>{title}</Text>
    </Pressable>
  );
}

/** The single accent call to action on a screen. */
export function PrimaryButton(props: ButtonProps) {
  return <Button {...props} tone="primary" />;
}

/** Bordered action for anything that should not compete with the primary button. */
export function SecondaryButton(props: ButtonProps) {
  return <Button {...props} tone="secondary" />;
}

/** Destructive action. Reads as a warning without shouting. */
export function DangerButton(props: ButtonProps) {
  return <Button {...props} tone="danger" />;
}
