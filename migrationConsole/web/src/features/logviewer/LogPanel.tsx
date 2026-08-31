import {
  type CSSProperties,
  type KeyboardEvent as ReactKeyboardEvent,
  type PointerEvent as ReactPointerEvent,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { useQuery } from "@tanstack/react-query";
import {
  CircleAlert,
  Check,
  ChevronDown,
  ChevronUp,
  Clipboard,
  Download,
  ExternalLink,
  LoaderCircle,
  Logs,
  Pause,
  Play,
  RefreshCw,
  Square,
  X,
} from "lucide-react";

import {
  getLogPage,
  getLogTargets,
  logEventsUrl,
  startLogStream,
  stopLogStream,
  type LogEvent,
  type LogStream,
  type LogStreamStatus,
} from "../../api/client";
import { useEscapeCancel } from "../../hooks/useEscapeCancel";
import { classifyLogSeverity } from "./logSeverity";


type ConnectionState =
  "idle" | "connecting" | "live" | "reconnecting" | "ended" | "stopped";

const DEFAULT_SOURCE_WIDTH = 420;
const MIN_SOURCE_WIDTH = 140;
const MIN_MESSAGE_WIDTH = 320;
const TIME_COLUMN_WIDTH = 82;


function mergeEvents(
  current: LogEvent[],
  incoming: LogEvent[],
): LogEvent[] {
  const events = new Map<number, LogEvent>();
  for (const event of current) events.set(event.sequence, event);
  for (const event of incoming) events.set(event.sequence, event);
  return [...events.values()]
    .sort((left, right) => left.sequence - right.sequence)
    .slice(-10_000);
}


function eventText(event: LogEvent): string {
  const timestamp = event.timestamp ?? event.receivedAt;
  const identity = `${event.podName}/${event.container}`;
  return `${timestamp} ${identity} ${event.message}`;
}


function logLineClassName(event: LogEvent): string {
  const severity = classifyLogSeverity(event.message, event.kind);
  return [
    "log-line",
    severity ? `log-line-${severity}` : "",
  ].filter(Boolean).join(" ");
}


export function LogPanel({
  nodeId,
  onClose,
  standalone = false,
}: Readonly<{
  nodeId: string;
  onClose: () => void;
  standalone?: boolean;
}>) {
  const inventory = useQuery({
    queryKey: ["log-targets", nodeId],
    queryFn: () => getLogTargets(nodeId),
    retry: false,
  });
  const [selectedId, setSelectedId] = useState("");
  const [tailLines, setTailLines] = useState(500);
  const [follow, setFollow] = useState(true);
  const [stream, setStream] = useState<LogStream | null>(null);
  const [events, setEvents] = useState<LogEvent[]>([]);
  const [beforeCursor, setBeforeCursor] = useState<string | null>(null);
  const [atAvailableStart, setAtAvailableStart] = useState(true);
  const [historyTruncated, setHistoryTruncated] = useState(false);
  const [connection, setConnection] =
    useState<ConnectionState>("idle");
  const [autoFollowPending, setAutoFollowPending] = useState(false);
  const [paused, setPaused] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [sourceWidth, setSourceWidth] = useState(DEFAULT_SOURCE_WIDTH);
  const [activeIssueSequence, setActiveIssueSequence] =
    useState<number | null>(null);
  const viewerRef = useRef<HTMLDivElement | null>(null);
  const pinToBottomRef = useRef(true);
  const sourceRef = useRef<EventSource | null>(null);
  const streamIdRef = useRef<string | null>(null);
  const autoStartedRef = useRef(false);
  const autoFollowTimerRef = useRef<number | null>(null);
  const followRef = useRef(follow);
  const sourceResizeRef = useRef<{
    pointerId: number;
    startX: number;
    startWidth: number;
  } | null>(null);

  const selected = useMemo(
    () => inventory.data?.targets.find(
      (target) => target.id === selectedId,
    ) ?? null,
    [inventory.data, selectedId],
  );
  const issues = useMemo(
    () => events.flatMap((event) => {
      const severity = classifyLogSeverity(event.message, event.kind);
      return severity ? [{ event, severity }] : [];
    }),
    [events],
  );
  const errorCount = issues.filter(
    ({ severity }) => severity === "error",
  ).length;
  const warningCount = issues.length - errorCount;
  const logViewerStyle = {
    "--log-source-width": `${sourceWidth}px`,
  } as CSSProperties;

  useEffect(() => {
    const targets = inventory.data?.targets ?? [];
    setSelectedId((current) => (
      targets.some((target) => target.id === current)
        ? current
        : targets[0]?.id ?? ""
    ));
  }, [inventory.data]);

  useEffect(() => {
    if (selected && !selected.supportsFollow) setFollow(false);
  }, [selected]);

  useEffect(() => {
    streamIdRef.current = stream?.id ?? null;
  }, [stream?.id]);

  useEffect(() => {
    followRef.current = follow;
  }, [follow]);

  useEffect(() => () => {
    if (autoFollowTimerRef.current !== null) {
      globalThis.clearTimeout(autoFollowTimerRef.current);
    }
    sourceRef.current?.close();
    if (streamIdRef.current) {
      void stopLogStream(streamIdRef.current);
    }
  }, []);

  useEffect(() => {
    sourceRef.current?.close();
    sourceRef.current = null;
    if (!stream || !follow || stream.state !== "following") return;

    const lastSequence = events.at(-1)?.sequence ?? 0;
    const source = new EventSource(logEventsUrl(stream.id, lastSequence));
    sourceRef.current = source;
    setConnection("connecting");
    source.onopen = () => setConnection("live");
    source.onerror = () => setConnection("reconnecting");
    source.addEventListener("log", (rawEvent) => {
      const event = JSON.parse(
        (rawEvent as MessageEvent<string>).data,
      ) as LogEvent;
      setEvents((current) => mergeEvents(current, [event]));
      setConnection("live");
    });
    source.addEventListener("stream-state", (rawEvent) => {
      const status = JSON.parse(
        (rawEvent as MessageEvent<string>).data,
      ) as LogStreamStatus;
      setStream((current) => (
        current ? { ...current, state: status.state } : current
      ));
      setConnection(
        status.state === "stopped" ? "stopped" : "ended",
      );
      if (status.message) setError(status.message);
      source.close();
    });
    return () => {
      source.close();
      if (sourceRef.current === source) sourceRef.current = null;
    };
    // Existing events are intentionally excluded: EventSource owns its cursor.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [follow, stream?.id, stream?.state]);

  useLayoutEffect(() => {
    const viewer = viewerRef.current;
    if (viewer && pinToBottomRef.current && !paused) {
      viewer.scrollTop = viewer.scrollHeight;
    }
  }, [events, paused]);

  const stop = async () => {
    if (!stream) return;
    setBusy(true);
    setError(null);
    sourceRef.current?.close();
    try {
      const status = await stopLogStream(stream.id);
      if (streamIdRef.current === stream.id) {
        streamIdRef.current = null;
      }
      setStream((current) => (
        current ? { ...current, state: status.state } : current
      ));
      setConnection("stopped");
    } catch (stopError) {
      setError((stopError as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const start = async (
    requestedFollow = follow,
    requestedTarget = selected,
  ) => {
    if (!requestedTarget) return;
    if (streamIdRef.current) {
      await stopLogStream(streamIdRef.current);
      streamIdRef.current = null;
    }
    setBusy(true);
    setError(null);
    setPaused(false);
    pinToBottomRef.current = true;
    try {
      const next = await startLogStream(requestedTarget.id, {
        tailLines,
        follow: requestedFollow && requestedTarget.supportsFollow,
        pageSize: Math.min(tailLines, 1000),
      });
      setStream(next);
      setEvents(next.page.events);
      setBeforeCursor(next.page.beforeCursor ?? null);
      setAtAvailableStart(next.page.atAvailableStart);
      setHistoryTruncated(next.page.historyTruncated);
      setConnection(next.state === "following" ? "connecting" : "ended");
      return next;
    } catch (startError) {
      setError((startError as Error).message);
      return undefined;
    } finally {
      setBusy(false);
    }
  };

  useEffect(() => {
    if (
      !standalone
      || !selected
      || autoStartedRef.current
    ) {
      return;
    }
    autoStartedRef.current = true;
    setAutoFollowPending(true);
    void start(false).then((initialStream) => {
      if (!initialStream) {
        setAutoFollowPending(false);
        return;
      }
      autoFollowTimerRef.current = globalThis.setTimeout(() => {
        autoFollowTimerRef.current = null;
        setAutoFollowPending(false);
        if (followRef.current) void start(true);
      }, 3000);
    });
    // The standalone viewer starts once for the inventory's initial target.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selected?.id, standalone]);

  const loadOlder = async () => {
    if (!stream || !beforeCursor) return;
    setBusy(true);
    setError(null);
    try {
      const page = await getLogPage(stream.id, {
        before: beforeCursor,
        limit: 200,
      });
      setEvents((current) => mergeEvents(page.events, current));
      setBeforeCursor(page.beforeCursor ?? null);
      setAtAvailableStart(page.atAvailableStart);
      setHistoryTruncated(page.historyTruncated);
    } catch (pageError) {
      setError((pageError as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const rendered = events.map(eventText).join("\n");

  const clampSourceWidth = (value: number) => {
    const viewerWidth = viewerRef.current?.clientWidth || 1000;
    const maximum = Math.max(
      MIN_SOURCE_WIDTH,
      viewerWidth - TIME_COLUMN_WIDTH - MIN_MESSAGE_WIDTH,
    );
    return Math.min(maximum, Math.max(MIN_SOURCE_WIDTH, value));
  };

  const beginSourceResize = (
    event: ReactPointerEvent<HTMLDivElement>,
  ) => {
    sourceResizeRef.current = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startWidth: sourceWidth,
    };
    event.currentTarget.setPointerCapture?.(event.pointerId);
    event.preventDefault();
  };

  const continueSourceResize = (
    event: ReactPointerEvent<HTMLDivElement>,
  ) => {
    const resize = sourceResizeRef.current;
    if (!resize || resize.pointerId !== event.pointerId) return;
    setSourceWidth(clampSourceWidth(
      resize.startWidth + event.clientX - resize.startX,
    ));
  };

  const endSourceResize = (
    event: ReactPointerEvent<HTMLDivElement>,
  ) => {
    if (sourceResizeRef.current?.pointerId !== event.pointerId) return;
    sourceResizeRef.current = null;
    event.currentTarget.releasePointerCapture?.(event.pointerId);
  };

  const resizeSourceWithKeyboard = (
    event: ReactKeyboardEvent<HTMLDivElement>,
  ) => {
    if (!["ArrowLeft", "ArrowRight", "Home"].includes(event.key)) return;
    event.preventDefault();
    if (event.key === "Home") {
      setSourceWidth(clampSourceWidth(DEFAULT_SOURCE_WIDTH));
      return;
    }
    setSourceWidth((current) => clampSourceWidth(
      current + (event.key === "ArrowRight" ? 16 : -16),
    ));
  };

  const jumpToIssue = (direction: 1 | -1) => {
    if (issues.length === 0) return;
    const current = issues.findIndex(
      ({ event }) => event.sequence === activeIssueSequence,
    );
    const next = current < 0
      ? (direction > 0 ? 0 : issues.length - 1)
      : (current + direction + issues.length) % issues.length;
    const sequence = issues[next].event.sequence;
    setActiveIssueSequence(sequence);
    pinToBottomRef.current = false;
    viewerRef.current
      ?.querySelector<HTMLElement>(`[data-log-sequence="${sequence}"]`)
      ?.scrollIntoView?.({ block: "center" });
  };

  const copy = async () => {
    await navigator.clipboard.writeText(rendered);
    setCopied(true);
    globalThis.setTimeout(() => setCopied(false), 1400);
  };

  const download = () => {
    const blob = new Blob([rendered], { type: "text/plain;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `${selected?.podName ?? "workflow"}-logs.txt`;
    link.click();
    URL.revokeObjectURL(url);
  };

  const close = () => {
    if (!stream || stream.state !== "following") {
      onClose();
      return;
    }
    void stop().finally(onClose);
  };
  const panelRef = useEscapeCancel<HTMLElement>(close);

  return (
    <section
      aria-label="Managed logs"
      className="log-panel workspace-section"
      data-escape-cancel-layer
      ref={panelRef}
    >
      <header className="output-panel-header">
        <div>
          <Logs aria-hidden="true" />
          <span>
            <small>
              Managed logs for {
                (inventory.data?.subjectKind ?? "item").replaceAll("-", " ")
              }
            </small>
            <h3>{inventory.data?.subjectLabel ?? "Loading log context"}</h3>
            <strong className="log-subject">
              Target: {selected?.label ?? "Select a target"}
            </strong>
          </span>
        </div>
        {!standalone ? (
          <a
            aria-label="Open logs in new tab"
            className="icon-button"
            href={`/logs?${new URLSearchParams({ nodeId }).toString()}`}
            rel="noopener noreferrer"
            target="_blank"
            title="Open logs in new tab"
          >
            <ExternalLink aria-hidden="true" />
          </a>
        ) : null}
        <button
          aria-label={standalone ? "Close log window" : "Close logs"}
          className="icon-button"
          onClick={close}
          title={standalone ? "Close log window" : "Close logs"}
          type="button"
        >
          <X aria-hidden="true" />
        </button>
      </header>

      {inventory.isPending ? (
        <div className="output-state" role="status">
          <LoaderCircle className="spin" aria-hidden="true" />
          Finding pod containers
        </div>
      ) : inventory.isError ? (
        <div className="output-state output-error" role="alert">
          <span>{inventory.error.message}</span>
          <button onClick={() => void inventory.refetch()} type="button">
            <RefreshCw aria-hidden="true" />
            Retry
          </button>
        </div>
      ) : inventory.data.targets.length === 0 ? (
        <div className="output-state">No pod containers are available.</div>
      ) : (
        <>
          <div className="log-retention-notice" role="note">
            <CircleAlert aria-hidden="true" />
            <span>
              Kubernetes logs are temporary. Logs disappear when Kubernetes
              removes or replaces pods, Jobs, or CronJobs.
            </span>
            {inventory.data.externalLogsUrl ? (
              <a
                href={inventory.data.externalLogsUrl}
                rel="noopener noreferrer"
                target="_blank"
              >
                Open CloudWatch log group
                <ExternalLink aria-hidden="true" />
              </a>
            ) : null}
          </div>
          {inventory.data.message ? (
            <p className="log-inventory-message">
              {inventory.data.message}
            </p>
          ) : null}
          <div className="log-setup">
            <label>
              <span>Target</span>
              <select
                aria-label="Log target"
                disabled={busy}
                onChange={(event) => {
                  const nextId = event.target.value;
                  const nextTarget = inventory.data.targets.find(
                    (target) => target.id === nextId,
                  );
                  setSelectedId(nextId);
                  if (autoFollowTimerRef.current !== null) {
                    globalThis.clearTimeout(autoFollowTimerRef.current);
                    autoFollowTimerRef.current = null;
                    setAutoFollowPending(false);
                  }
                  if (stream && nextTarget) {
                    void start(followRef.current, nextTarget);
                  }
                }}
                value={selectedId}
              >
                {inventory.data.targets.map((target) => (
                  <option key={target.id} value={target.id}>
                    {target.label}
                  </option>
                ))}
              </select>
            </label>
            <label>
              <span>Available tail</span>
              <select
                aria-label="Log history lines"
                disabled={stream?.state === "following"}
                onChange={(event) => setTailLines(Number(event.target.value))}
                value={tailLines}
              >
                <option value={100}>100 lines</option>
                <option value={500}>500 lines</option>
                <option value={1000}>1,000 lines</option>
                <option value={5000}>5,000 lines</option>
              </select>
            </label>
            <label className="log-follow-toggle">
              <input
                checked={follow && (selected?.supportsFollow ?? false)}
                disabled={
                  !selected?.supportsFollow
                  || busy
                }
                onChange={(event) => {
                  const nextFollow = event.target.checked;
                  setFollow(nextFollow);
                  if (!nextFollow && autoFollowTimerRef.current !== null) {
                    globalThis.clearTimeout(autoFollowTimerRef.current);
                    autoFollowTimerRef.current = null;
                    setAutoFollowPending(false);
                  }
                  if (!nextFollow && stream?.state === "following") {
                    void stop();
                  } else if (
                    nextFollow
                    && stream
                  ) {
                    void start(true);
                  }
                }}
                type="checkbox"
              />
              <span>Follow</span>
            </label>
            <button
              disabled={!selected || busy || stream?.state === "following"}
              onClick={() => void start()}
              type="button"
            >
              <Play aria-hidden="true" />
              Start logs
            </button>
          </div>

          {stream ? (
            <>
              <div className="log-toolbar">
                <div className="log-toolbar-status">
                  <span className={`log-connection state-${connection}`}>
                    {autoFollowPending
                      ? "Follow starts in 3 seconds"
                      : paused ? "Paused" : connection}
                  </span>
                  {issues.length > 0 ? (
                    <div
                      aria-label="Highlighted log lines"
                      className="log-issue-summary"
                      role="status"
                    >
                      <CircleAlert aria-hidden="true" />
                      {errorCount > 0 ? (
                        <span className="log-error-count">
                          {errorCount} error{errorCount === 1 ? "" : "s"}
                        </span>
                      ) : null}
                      {warningCount > 0 ? (
                        <span className="log-warning-count">
                          {warningCount} warning
                          {warningCount === 1 ? "" : "s"}
                        </span>
                      ) : null}
                      <button
                        aria-label="Previous highlighted line"
                        onClick={() => jumpToIssue(-1)}
                        title="Previous highlighted line"
                        type="button"
                      >
                        <ChevronUp aria-hidden="true" />
                      </button>
                      <button
                        aria-label="Next highlighted line"
                        onClick={() => jumpToIssue(1)}
                        title="Next highlighted line"
                        type="button"
                      >
                        <ChevronDown aria-hidden="true" />
                      </button>
                    </div>
                  ) : null}
                </div>
                <button
                  disabled={busy || atAvailableStart || !beforeCursor}
                  onClick={() => void loadOlder()}
                  type="button"
                >
                  Load older
                </button>
                {stream.state === "following" ? (
                  <button
                    onClick={() => setPaused((current) => !current)}
                    type="button"
                  >
                    {paused
                      ? <Play aria-hidden="true" />
                      : <Pause aria-hidden="true" />}
                    {paused ? "Resume" : "Pause"}
                  </button>
                ) : null}
                <button
                  disabled={busy || stream.state === "stopped"}
                  onClick={() => void stop()}
                  type="button"
                >
                  <Square aria-hidden="true" />
                  Stop
                </button>
                <button
                  disabled={events.length === 0}
                  onClick={() => void copy()}
                  type="button"
                >
                  {copied
                    ? <Check aria-hidden="true" />
                    : <Clipboard aria-hidden="true" />}
                  {copied ? "Copied" : "Copy"}
                </button>
                <button
                  disabled={events.length === 0}
                  onClick={download}
                  type="button"
                >
                  <Download aria-hidden="true" />
                  Download
                </button>
              </div>
              {historyTruncated && atAvailableStart ? (
                <p className="log-boundary">
                  Beginning of the bounded Kubernetes tail.
                </p>
              ) : null}
              {error ? (
                <div className="log-error" role="alert">{error}</div>
              ) : null}
              <div
                aria-label="Log output"
                className="log-viewer"
                onScroll={(event) => {
                  const viewer = event.currentTarget;
                  pinToBottomRef.current = (
                    viewer.scrollHeight
                    - viewer.scrollTop
                    - viewer.clientHeight
                  ) < 24;
                }}
                ref={viewerRef}
                role="log"
                style={logViewerStyle}
              >
                <div className="log-column-header">
                  <span>Time</span>
                  <span>Source</span>
                  <span>Message</span>
                  <button
                    aria-label="Resize source column"
                    aria-orientation="vertical"
                    aria-valuemax={1000}
                    aria-valuemin={MIN_SOURCE_WIDTH}
                    aria-valuenow={sourceWidth}
                    className="log-source-resizer"
                    onDoubleClick={() => {
                      setSourceWidth(clampSourceWidth(DEFAULT_SOURCE_WIDTH));
                    }}
                    onKeyDown={resizeSourceWithKeyboard}
                    onPointerCancel={endSourceResize}
                    onPointerDown={beginSourceResize}
                    onPointerMove={continueSourceResize}
                    onPointerUp={endSourceResize}
                    role="separator"
                    title="Drag to resize the source column; double-click to reset"
                    type="button"
                  />
                </div>
                {events.length === 0 ? (
                  <div className="log-empty">No log lines in this tail.</div>
                ) : events.map((event) => (
                  <div
                    className={[
                      logLineClassName(event),
                      activeIssueSequence === event.sequence
                        ? "log-line-active"
                        : "",
                    ].filter(Boolean).join(" ")}
                    data-log-sequence={event.sequence}
                    key={event.sequence}
                  >
                    <time>{event.timestamp
                      ? new Date(event.timestamp).toLocaleTimeString()
                      : ""}</time>
                    <span title={`${event.podName} / ${event.container}`}>
                      {event.podName}/{event.container}
                    </span>
                    <code>{event.message}</code>
                  </div>
                ))}
              </div>
            </>
          ) : error ? (
            <div className="log-error" role="alert">{error}</div>
          ) : null}
        </>
      )}
    </section>
  );
}
