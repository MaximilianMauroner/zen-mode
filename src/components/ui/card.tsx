import { Children, type ReactNode } from 'react';
import { Pressable, Text, View } from 'react-native';
import { ChevronRight, type LucideIcon } from 'lucide-react-native';
import { colors } from '@/theme/colors';

import { StatusPill, type PillTone } from '@/components/ui/pill';

const TONE_TEXT: Record<PillTone, string> = { accent: 'text-accent', danger: 'text-danger', neutral: 'text-muted' };

/** Standard surface. `emphasis` lifts a card to the top of the visual hierarchy. */
export function Card({ children, emphasis = false, className = '' }: { children: ReactNode; emphasis?: boolean; className?: string }) {
  const surface = emphasis ? 'border-accentLine bg-accentBg' : 'border-line bg-panel';
  return <View className={`rounded-3xl border p-4 ${surface} ${className}`}>{children}</View>;
}

/** Short label above a group of controls or a section of the page. */
export function SectionLabel({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <Text className={`text-support font-semibold text-muted ${className}`}>{children}</Text>;
}

/** Card header line: a label on the left, an optional state pill on the right. */
export function CardHeader({ label, icon, pill }: { label: string; icon?: ReactNode; pill?: { label: string; tone?: PillTone } }) {
  return (
    <View className="flex-row items-center justify-between">
      <View className="flex-row items-center">
        {icon}
        <SectionLabel className={icon ? 'ml-2' : ''}>{label}</SectionLabel>
      </View>
      {pill ? <StatusPill label={pill.label} tone={pill.tone} /> : null}
    </View>
  );
}

/**
 * Grouped list surface. Draws separators between children only, so the last row
 * never leaves a dangling rule inside the rounded edge. Conditional `null`
 * children are dropped before separators are placed.
 */
export function RowGroup({ children, className = '' }: { children: ReactNode; className?: string }) {
  const rows = Children.toArray(children);
  return (
    <View className={`overflow-hidden rounded-3xl border border-line bg-panel px-4 ${className}`}>
      {rows.map((row, index) => (
        <View key={index} className={index === 0 ? '' : 'border-t border-line'}>
          {row}
        </View>
      ))}
    </View>
  );
}

/**
 * One line inside a `RowGroup`. Becomes pressable when `onPress` is given.
 * `statusLabel` is a short state word beside the label; `value` is a pill on
 * the trailing edge for counts and states that need to scan as a column.
 */
export function Row({
  icon: Icon,
  label,
  detail,
  value,
  statusLabel,
  tone = 'neutral',
  disabled,
  accessibilityLabel,
  onPress,
}: {
  icon?: LucideIcon;
  label: string;
  detail?: string;
  value?: string;
  statusLabel?: string;
  tone?: PillTone;
  disabled?: boolean;
  accessibilityLabel?: string;
  onPress?: () => void;
}) {
  const content = (
    <>
      {Icon ? <View className="mr-3"><Icon color={colors.accent} size={20} /></View> : null}
      <View className="flex-1 pr-3">
        <View className="flex-row flex-wrap items-baseline justify-between gap-x-3 gap-y-0.5">
          <Text className="text-item font-semibold text-copy">{label}</Text>
          {statusLabel ? <Text className={`text-caption font-semibold ${TONE_TEXT[tone]}`}>{statusLabel}</Text> : null}
        </View>
        {detail ? <Text className="mt-0.5 text-support text-muted">{detail}</Text> : null}
      </View>
      {value ? <StatusPill label={value} tone={tone} /> : null}
      {onPress ? <ChevronRight color={colors.muted} size={18} style={{ marginLeft: 8 }} /> : null}
    </>
  );

  if (!onPress) return <View className="min-h-14 flex-row items-center py-3">{content}</View>;

  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel}
      accessibilityState={{ disabled: Boolean(disabled) }}
      className={`min-h-14 flex-row items-center py-3 ${disabled ? 'opacity-40' : 'active:opacity-60'}`}
      disabled={disabled}
      onPress={onPress}>
      {content}
    </Pressable>
  );
}
