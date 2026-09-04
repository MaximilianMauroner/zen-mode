import palette from './palette.json';

/**
 * Runtime color tokens. Use these for props that cannot take a Tailwind class,
 * such as icon `color` or `RefreshControl` tints. Class names should keep using
 * the matching Tailwind token (`text-accent`, `bg-panel`, ...) so both sides
 * stay driven by `palette.json`.
 */
export const colors = palette;

export type ColorToken = keyof typeof palette;
