import {
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Check,
  Clipboard,
  Download,
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


type ConnectionState =
  "idle" | "connecting" | "live" | "reconnecting" | "ended" | "stopped";


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


export function LogPanel({
  nodeId,
  onClose,
}: Readonly<{
  nodeId: string;
  onClose: () => void;
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
  const [paused, setPaused] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const viewerRef = useRef<HTMLDivElement | null>(null);
  const pinToBottomRef = useRef(true);
  const sourceRef = useRef<EventSource | null>(null);
  const streamIdRef = useRef<string | null>(null);

  const selected = useMemo(
    () => inventory.data?.targets.find(
      (target) => target.id === selectedId,
    ) ?? null,
    [inventory.data, selectedId],
  );

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

  useEffect(() => () => {
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

  const start = async () => {
    if (!selected) return;
    if (stream && stream.state === "following") await stop();
    setBusy(true);
    setError(null);
    setPaused(false);
    pinToBottomRef.current = true;
    try {
      const next = await startLogStream(selected.id, {
        tailLines,
        follow: follow && selected.supportsFollow,
      });
      setStream(next);
      setEvents(next.page.events);
      setBeforeCursor(next.page.beforeCursor ?? null);
      setAtAvailableStart(next.page.atAvailableStart);
      setHistoryTruncated(next.page.historyTruncated);
      setConnection(next.state === "following" ? "connecting" : "ended");
    } catch (startError) {
      setError((startError as Error).message);
    } finally {
      setBusy(false);
    }
  };

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
            <small>Managed logs</small>
            <h3>{selected?.label ?? "Select a target"}</h3>
          </span>
        </div>
        <button
          aria-label="Close logs"
          className="icon-button"
          onClick={close}
          title="Close logs"
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
          <div className="log-setup">
            <label>
              <span>Target</span>
              <select
                aria-label="Log target"
                disabled={stream?.state === "following"}
                onChange={(event) => setSelectedId(event.target.value)}
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
                  || stream?.state === "following"
                }
                onChange={(event) => setFollow(event.target.checked)}
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
                <span className={`log-connection state-${connection}`}>
                  {paused ? "Paused" : connection}
                </span>
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
              >
                {events.length === 0 ? (
                  <div className="log-empty">No log lines in this tail.</div>
                ) : events.map((event) => (
                  <div
                    className={`log-line log-line-${event.kind}`}
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
