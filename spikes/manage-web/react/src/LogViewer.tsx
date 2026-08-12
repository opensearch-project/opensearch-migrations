import { useEffect, useRef } from "react";
import { Pause, Play, Trash2 } from "lucide-react";

interface LogViewerProps {
  lines: ReadonlyArray<string>;
  running: boolean;
  onStart: () => void;
  onStop: () => void;
  onClear: () => void;
}

export function LogViewer({
  lines,
  running,
  onStart,
  onStop,
  onClear,
}: LogViewerProps) {
  const tailRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (running) {
      tailRef.current?.scrollIntoView({ block: "end" });
    }
  }, [lines, running]);

  return (
    <div className="logs-tool">
      <div className="logs-toolbar">
        <div className="log-source">
          <span className={running ? "pulse-dot" : "idle-dot"} />
          <div>
            <strong>traffic-replayer</strong>
            <span>replayer-0 · migration-console</span>
          </div>
        </div>
        <div className="toolbar-actions">
          <button
            className={`button ${running ? "danger-subtle" : "primary"}`}
            type="button"
            data-testid="log-stream-control"
            onClick={running ? onStop : onStart}
          >
            {running ? <Pause aria-hidden="true" /> : <Play aria-hidden="true" />}
            {running ? "Stop stream" : "Start stream"}
          </button>
          <button
            className="icon-button"
            type="button"
            data-testid="log-clear-control"
            aria-label="Clear logs"
            title="Clear logs"
            disabled={lines.length === 0}
            onClick={onClear}
          >
            <Trash2 aria-hidden="true" />
          </button>
        </div>
      </div>
      <div className="log-output" role="log" aria-label="Resource logs">
        {lines.length === 0 ? (
          <span className="log-placeholder">Log stream is stopped.</span>
        ) : (
          lines.map((line, index) => (
            <div key={`${index}-${line}`} className="log-line">
              <span>{String(index + 1).padStart(3, "0")}</span>
              <code>{line}</code>
            </div>
          ))
        )}
        <div ref={tailRef} />
      </div>
    </div>
  );
}
