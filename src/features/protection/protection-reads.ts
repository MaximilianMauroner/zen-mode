import type { ReadState } from './control-state';

export type ReadResult<T> = { kind: 'ready'; value: T } | { kind: 'android-required' };

export type ProtectionRead<T> = {
  getSnapshot(): ReadState<T>;
  subscribe(listener: () => void): () => void;
  /**
   * While attached, waits for a read started after this call. Detachment releases
   * queued requests without reading. A detached call resolves without reading.
   */
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
 * Detached, it starts no read and calls no listener. Requests during a read
 * share one follow-up read.
 */
export function createProtectionRead<T>({ read, failure, whileWatched }: Options<T>): ProtectionRead<T> {
  let state: ReadState<T> = LOADING;
  const listeners = new Set<() => void>();
  let watchers = 0;
  let stopWatching: (() => void) | undefined;
  let reading: Promise<void> | null = null;
  let orphaned = false;
  let followUp: { promise: Promise<void>; resolve: () => void } | null = null;

  const attached = () => listeners.size > 0 || watchers > 0;

  const detachIfLast = () => {
    if (attached()) return;
    state = LOADING;
    orphaned = reading !== null;
    followUp?.resolve();
    followUp = null;
  };

  const publish = (next: ReadState<T>) => {
    if (next === state) return;
    state = next;
    listeners.forEach((listener) => listener());
  };

  const settle = (next: ReadState<T>) => {
    if (!orphaned) publish(next);
  };

  const start = (): Promise<void> => {
    orphaned = false;
    publish(state.kind === 'ready' ? { ...state, refreshing: true } : state.kind === 'failed' ? LOADING : state);
    reading = read().then(
      (result) => settle(result.kind === 'ready' ? { ...result, refreshing: false } : ANDROID_REQUIRED),
      () => settle({ kind: 'failed', message: failure }),
    ).finally(() => {
      reading = null;
      const next = followUp;
      followUp = null;
      if (next) void start().then(next.resolve);
    });
    return reading;
  };

  const refresh = () => {
    if (!attached()) return Promise.resolve();
    if (!reading) return start();
    if (!followUp) {
      let resolve = () => {};
      const promise = new Promise<void>((done) => {
        resolve = done;
      });
      followUp = { promise, resolve };
    }
    return followUp.promise;
  };

  return {
    getSnapshot: () => state,
    subscribe(listener) {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
        detachIfLast();
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
        if (watchers === 0) {
          stopWatching?.();
          stopWatching = undefined;
        }
        detachIfLast();
      };
    },
  };
}
