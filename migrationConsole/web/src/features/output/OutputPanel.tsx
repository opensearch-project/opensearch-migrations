import { useEffect, useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Check,
  Clipboard,
  Download,
  FileOutput,
  LoaderCircle,
  RefreshCw,
  X,
} from "lucide-react";

import {
  getOutputContent,
  getOutputs,
  outputDownloadUrl,
} from "../../api/client";


function displayContent(content: string, contentType: string): string {
  if (contentType !== "application/json") return content;
  try {
    return JSON.stringify(JSON.parse(content), null, 2);
  } catch {
    return content;
  }
}


export function OutputPanel({
  targetId,
  onClose,
}: {
  targetId: string;
  onClose: () => void;
}) {
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const inventory = useQuery({
    queryKey: ["managed-outputs", targetId],
    queryFn: () => getOutputs(targetId),
  });

  useEffect(() => {
    const outputs = inventory.data?.outputs ?? [];
    setSelectedId((current) => (
      current && outputs.some((output) => output.id === current)
        ? current
        : outputs.find((output) => output.targetId === targetId)?.id
          ?? outputs[0]?.id
          ?? null
    ));
  }, [inventory.data, targetId]);

  const selected = useMemo(
    () => inventory.data?.outputs.find(
      (output) => output.id === selectedId,
    ) ?? null,
    [inventory.data, selectedId],
  );
  const content = useQuery({
    queryKey: ["managed-output-content", selectedId],
    queryFn: () => getOutputContent(selectedId ?? ""),
    enabled: selectedId !== null,
    retry: false,
  });
  const rendered = (
    content.data?.content !== null
    && content.data?.content !== undefined
    && selected
      ? displayContent(content.data.content, selected.contentType)
      : null
  );

  const copy = async () => {
    if (rendered === null) return;
    await navigator.clipboard.writeText(rendered);
    setCopied(true);
    window.setTimeout(() => setCopied(false), 1400);
  };

  return (
    <section className="output-panel workspace-section" aria-label="Managed output">
      <header className="output-panel-header">
        <div>
          <FileOutput aria-hidden="true" />
          <span>
            <small>Managed output</small>
            <h3>{selected?.stage ?? "Output"}</h3>
          </span>
        </div>
        <button
          aria-label="Close output"
          className="icon-button"
          onClick={onClose}
          title="Close output"
          type="button"
        >
          <X aria-hidden="true" />
        </button>
      </header>
      {inventory.isPending ? (
        <div className="output-state" role="status">
          <LoaderCircle className="spin" aria-hidden="true" />
          Loading output references
        </div>
      ) : inventory.isError ? (
        <div className="output-state output-error" role="alert">
          <span>{inventory.error.message}</span>
          <button onClick={() => void inventory.refetch()} type="button">
            <RefreshCw aria-hidden="true" />
            Retry
          </button>
        </div>
      ) : inventory.data.outputs.length === 0 ? (
        <div className="output-state">No managed output is available.</div>
      ) : (
        <>
          <div className="output-stage-tabs" role="tablist" aria-label="Output stages">
            {inventory.data.outputs.map((output) => (
              <button
                aria-selected={output.id === selectedId}
                className={output.id === selectedId ? "active" : ""}
                key={output.id}
                onClick={() => setSelectedId(output.id)}
                role="tab"
                type="button"
              >
                <span>{output.stage}</span>
                <small>{output.attempt ?? "Current"}</small>
              </button>
            ))}
          </div>
          {selected ? (
            <dl className="output-context">
              <div>
                <dt>Resource</dt>
                <dd>{selected.resourceName}</dd>
              </div>
              <div>
                <dt>Stage</dt>
                <dd>{selected.stage}</dd>
              </div>
              <div>
                <dt>Attempt</dt>
                <dd>{selected.attempt ?? "Unknown"}</dd>
              </div>
              <div>
                <dt>Created</dt>
                <dd>{selected.timestamp
                  ? new Date(selected.timestamp).toLocaleString()
                  : "Unknown"}</dd>
              </div>
              <div className="output-source">
                <dt>Source</dt>
                <dd title={selected.source}>{selected.source}</dd>
              </div>
            </dl>
          ) : null}
          <div className="output-toolbar">
            <span>{selected?.contentType ?? "text/plain"}</span>
            <button
              disabled={rendered === null}
              onClick={() => void copy()}
              title="Copy complete inline output"
              type="button"
            >
              {copied
                ? <Check aria-hidden="true" />
                : <Clipboard aria-hidden="true" />}
              {copied ? "Copied" : "Copy"}
            </button>
            {selected ? (
              <a
                download
                href={outputDownloadUrl(selected.id)}
                title="Download complete output"
              >
                <Download aria-hidden="true" />
                Download
              </a>
            ) : null}
          </div>
          {content.isPending ? (
            <div className="output-state" role="status">
              <LoaderCircle className="spin" aria-hidden="true" />
              Reading output
            </div>
          ) : content.isError ? (
            <div className="output-state output-error" role="alert">
              <span>{content.error.message}</span>
              <button onClick={() => void content.refetch()} type="button">
                <RefreshCw aria-hidden="true" />
                Retry
              </button>
            </div>
          ) : rendered !== null ? (
            <pre className="output-content">{rendered}</pre>
          ) : (
            <div className="output-state">
              {content.data?.message ?? "Use download to read this output."}
            </div>
          )}
        </>
      )}
    </section>
  );
}
