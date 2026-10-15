/** What a screen knows about one native dataset. A failed read keeps no snapshot. */
export type ReadState<T> =
  | { kind: 'loading' }
  | { kind: 'ready'; value: T; refreshing: boolean }
  | { kind: 'failed'; message: string }
  | { kind: 'android-required' };

export function readValue<T>(read: ReadState<T>): T | null {
  return read.kind === 'ready' ? read.value : null;
}

export function isReading(read: ReadState<unknown>): boolean {
  return read.kind === 'loading' || (read.kind === 'ready' && read.refreshing);
}
