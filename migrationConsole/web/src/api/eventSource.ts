export type EventConnectionState = "connecting" | "live" | "reconnecting";

const EVENT_SOURCE_CLOSED = 2;
const MAX_RETRY_DELAY_MS = 15_000;

export interface ResilientEventSourceOptions {
  listeners: Record<string, (event: MessageEvent) => void>;
  onStateChange?: (state: EventConnectionState) => void;
  /** Called on every successful open after the first, so callers can
      refetch whatever changed while the stream was down. */
  onRecovered?: () => void;
}

/**
 * Connect an EventSource that survives permanently-closed connections.
 *
 * Browsers only auto-retry transient network errors; a non-200 response or
 * a server restart moves the source to CLOSED and it never reconnects on
 * its own. This helper rebuilds the source with capped backoff in that
 * case. Returns a dispose function.
 */
export function connectEventSource(
  url: string,
  options: ResilientEventSourceOptions,
): () => void {
  let source: EventSource | null = null;
  let retryTimer: number | null = null;
  let attempts = 0;
  let everOpened = false;
  let disposed = false;

  const scheduleRebuild = () => {
    if (disposed || retryTimer !== null) return;
    const delay = Math.min(
      MAX_RETRY_DELAY_MS,
      1000 * 2 ** Math.min(attempts, 4),
    );
    attempts += 1;
    retryTimer = globalThis.setTimeout(() => {
      retryTimer = null;
      open();
    }, delay);
  };

  const open = () => {
    if (disposed) return;
    const next = new EventSource(url);
    source = next;
    next.onopen = () => {
      attempts = 0;
      options.onStateChange?.("live");
      if (everOpened) options.onRecovered?.();
      everOpened = true;
    };
    next.onerror = () => {
      options.onStateChange?.("reconnecting");
      if (next.readyState === EVENT_SOURCE_CLOSED) {
        next.close();
        if (source === next) source = null;
        scheduleRebuild();
      }
    };
    Object.entries(options.listeners).forEach(([type, handler]) => {
      next.addEventListener(type, handler);
    });
  };

  options.onStateChange?.("connecting");
  open();
  return () => {
    disposed = true;
    if (retryTimer !== null) globalThis.clearTimeout(retryTimer);
    source?.close();
  };
}
