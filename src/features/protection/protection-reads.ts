import type { ReadState } from './control-state';

export type ReadResult<T> = { kind: 'ready'; value: T } | { kind: 'android-required' };

export type ProtectionRead<T> = {
  getSnapshot(): ReadState<T>;
  subscribe(listener: () => void): () => void;
  refresh(): Promise<void>;
  /** Refresh now and keep the background triggers while any watcher remains. Returns the release. */
  watch(): () => void;
};

type Options<T> = {
  read: () => Promise<ReadResult<T>>;
  failure: string;
  whileWatched?: (refresh: () => void) => () => void;
};

const LOADING = { kind: 'loading' } as const;
const ANDROID_REQUIRED = { kind: 'android-required' } as const;

/**
 * The only reader of one dataset. A subscriber or a watcher keeps it attached.
 * Detached, it starts no read and calls no listener.
 */
export function createProtectionRead<T>({ read, failure, whileWatched }: Options<T>): ProtectionRead<T> {
  let state: ReadState<T> = LOADING;
  const listeners = new Set<() => void>();
  let watchers = 0;
  let stopWatching: (() => void) | undefined;
  let reading: Promise<void> | null = null;

  const attached = () => listeners.size > 0 || watchers > 0;

  const publish = (next: ReadState<T>) => {
    if (next === state) return;
    state = next;
    listeners.forEach((listener) => listener());
  };

  const start = () => {
    publish(state.kind === 'ready' ? { ...state, refreshing: true } : state.kind === 'failed' ? LOADING : state);
    reading = read().then(
      (result) => publish(result.kind === 'ready' ? { ...result, refreshing: false } : ANDROID_REQUIRED),
      () => publish({ kind: 'failed', message: failure }),
    ).finally(() => {
      reading = null;
    });
    return reading;
  };

  const refresh = async () => {
    if (!attached() || reading) return;
    await start();
  };

  return {
    getSnapshot: () => state,
    subscribe(listener) {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    refresh,
    watch() {
      watchers += 1;
      if (watchers === 1) stopWatching = whileWatched?.(() => void refresh());
      void refresh();
      let released = false;
      return () => {
        if (released) return;
        released = true;
        watchers -= 1;
        if (watchers > 0) return;
        stopWatching?.();
        stopWatching = undefined;
      };
    },
  };
}
