import { Text } from 'react-native';

export type PillTone = 'accent' | 'danger' | 'neutral';

const TONE_CLASS: Record<PillTone, string> = {
  accent: 'border-accentLine bg-accentBg text-accent',
  danger: 'border-dangerLine bg-dangerBg text-danger',
  neutral: 'border-line bg-panel2 text-muted',
};

/** Compact state marker beside a row or card. Color repeats the text; it never replaces it. */
export function StatusPill({ label, tone = 'neutral' }: { label: string; tone?: PillTone }) {
  return <Text className={`overflow-hidden rounded-full border px-2.5 py-1 text-micro font-semibold ${TONE_CLASS[tone]}`}>{label}</Text>;
}
