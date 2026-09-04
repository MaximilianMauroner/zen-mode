import { Text } from 'react-native';

export type PillTone = 'accent' | 'danger' | 'neutral';

const TONE_CLASS: Record<PillTone, string> = {
  // Solid accent is the loudest chrome in the app. Keep it to one pill per screen.
  accent: 'border-accent bg-accent text-onAccent',
  danger: 'border-dangerLine bg-dangerBg text-danger',
  neutral: 'border-line bg-panel2 text-muted',
};

/** Compact uppercase state marker. `solid` opts an accent pill into the filled treatment. */
export function StatusPill({ label, tone = 'neutral', solid = false }: { label: string; tone?: PillTone; solid?: boolean }) {
  const toneClass = tone === 'accent' && !solid ? 'border-accentLine bg-accentBg text-accent' : TONE_CLASS[tone];
  return <Text className={`rounded-full border px-2.5 py-1 text-[10px] font-bold tracking-[0.14em] ${toneClass}`}>{label}</Text>;
}
