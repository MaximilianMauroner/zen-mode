import { Children, type ReactNode } from 'react';
import { Pressable, Text, View } from 'react-native';
import { ChevronRight, type LucideIcon } from 'lucide-react-native';
import { colors } from '@/theme/colors';

import { StatusPill, type PillTone } from '@/components/ui/pill';

/** Standard surface. `emphasis` lifts a card to the top of the visual hierarchy. */
export function Card({ children, emphasis = false, className = '' }: { children: ReactNode; emphasis?: boolean; className?: string }) {
  const surface = emphasis ? 'border-accentLine bg-accentBg' : 'border-line bg-panel';
  return <View className={`rounded-3xl border p-4 ${surface} ${className}`}>{children}</View>;
}

/** Eyebrow above a card group or a section of the page. */
export function SectionLabel({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <Text className={`text-[11px] font-bold tracking-[0.16em] text-faint ${className}`}>{children}</Text>;
}

/** Card header line: an eyebrow on the left, an optional state pill on the right. */
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
 * `destructive` marks a row that performs an action rather than reporting a
 * state, so it is tinted and carries no state pill.
 */
export function Row({
  icon: Icon,
  label,
  detail,
  value,
  statusLabel,
  tone = 'neutral',
  destructive = false,
  disabled,
  onPress,
}: {
  icon?: LucideIcon;
  label: string;
  detail: string;
  value?: string;
  statusLabel?: string;
  tone?: PillTone;
  destructive?: boolean;
  disabled?: boolean;
  onPress?: () => void;
}) {
  const content = (
    <>
      {Icon ? <View className="mr-3"><Icon color={colors.accent} size={20} /></View> : null}
      <View className="flex-1 pr-3">
        <View className="flex-row flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
          <Text className={`text-[15px] font-semibold ${destructive ? 'text-danger' : 'text-copy'}`}>{label}</Text>
          {statusLabel ? <Text className={`text-[12px] font-semibold ${tone === 'accent' ? 'text-accent' : tone === 'danger' ? 'text-danger' : 'text-muted'}`}>{statusLabel}</Text> : null}
        </View>
        <Text className="mt-0.5 text-[13px] leading-[18px] text-muted">{detail}</Text>
      </View>
      {value && !destructive ? <StatusPill label={value} tone={tone} /> : null}
      {onPress && !destructive ? <ChevronRight color={colors.muted} size={17} style={{ marginLeft: 8 }} /> : null}
    </>
  );

  if (!onPress) return <View className="flex-row items-center py-3.5">{content}</View>;

  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled: Boolean(disabled) }}
      className={`flex-row items-center py-3.5 ${disabled ? 'opacity-40' : 'active:opacity-60'}`}
      disabled={disabled}
      onPress={onPress}>
      {content}
    </Pressable>
  );
}
