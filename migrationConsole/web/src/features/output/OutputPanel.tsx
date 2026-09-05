import { useEffect, useMemo, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  AlertTriangle,
  Check,
  Clipboard,
  Download,
  FileOutput,
  LoaderCircle,
  RefreshCw,
  ShieldCheck,
  X,
} from "lucide-react";

import {
  approveTarget,
  getApprovalReview,
  getOutputContent,
  getOutputs,
  outputDownloadUrl,
} from "../../api/client";
import { useEscapeCancel } from "../../hooks/useEscapeCancel";
import type { ApprovalCandidate } from "../actions/approvals";


function displayContent(content: string, contentType: string): string {
  if (contentType !== "application/json") return content;
  try {
    return JSON.stringify(JSON.parse(content), null, 2);
  } catch {
    return content;
  }
}


export function OutputPanel({
  approval,
  onApprovalStarted,
  targetId,
  onClose,
}: Readonly<{
  approval?: ApprovalCandidate | null;
  onApprovalStarted?: (targetId: string) => void;
  targetId: string;
  onClose: () => void;
}>) {
  const queryClient = useQueryClient();
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [approving, setApproving] = useState(false);
  const [approvalAccepted, setApprovalAccepted] = useState(false);
  const [approvalProblem, setApprovalProblem] = useState("");
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
  const panelRef = useEscapeCancel<HTMLElement>(onClose);
  const approvalReview = useQuery({
    queryKey: ["approval-review", approval?.targetId],
    queryFn: () => getApprovalReview(approval?.targetId ?? ""),
    enabled: Boolean(approval),
    retry: false,
  });

  useEffect(() => {
    setApproving(false);
    setApprovalAccepted(false);
    setApprovalProblem("");
  }, [approval?.targetId]);

  const copy = async () => {
    if (rendered === null) return;
    await navigator.clipboard.writeText(rendered);
    setCopied(true);
    globalThis.setTimeout(() => setCopied(false), 1400);
  };

  const approve = async () => {
    if (!approval || !approvalReview.data) return;
    setApproving(true);
    setApprovalProblem("");
    try {
      await approveTarget(
        approval.targetId,
        approvalReview.data.gateRevision,
      );
      setApprovalAccepted(true);
      onApprovalStarted?.(approval.targetId);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["operations"] }),
        queryClient.invalidateQueries({ queryKey: ["manage-state"] }),
      ]);
    } catch (error) {
      setApprovalProblem(
        error instanceof Error ? error.message : String(error),
      );
    } finally {
      setApproving(false);
    }
  };

  return (
    <section
      aria-label="Managed output"
      className="output-panel workspace-section"
      data-escape-cancel-layer
      ref={panelRef}
    >
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
      {approval ? (
        <section
          aria-label="Output approval"
          className="output-approval"
        >
          <ShieldCheck aria-hidden="true" />
          <div>
            <strong>
              {approvalReview.data?.stage ?? approval.label}
            </strong>
            <span>
              Review this output, then approve to continue the workflow.
            </span>
          </div>
          {approvalAccepted ? (
            <span className="output-approval-accepted" role="status">
              <LoaderCircle className="spin" aria-hidden="true" />
              Approval accepted
            </span>
          ) : (
            <button
              className="primary-button"
              disabled={
                approving
                || approvalReview.isPending
                || !approvalReview.data
                || Boolean(approval.disabledReason)
              }
              onClick={() => void approve()}
              title={approval.disabledReason ?? undefined}
              type="button"
            >
              {approving
                ? <LoaderCircle className="spin" aria-hidden="true" />
                : <ShieldCheck aria-hidden="true" />}
              Approve
            </button>
          )}
          {approvalReview.isError ? (
            <div className="output-approval-error" role="alert">
              <AlertTriangle aria-hidden="true" />
              <span>{approvalReview.error.message}</span>
              <button
                onClick={() => void approvalReview.refetch()}
                type="button"
              >
                Retry
              </button>
            </div>
          ) : null}
          {approvalProblem ? (
            <div className="output-approval-error" role="alert">
              <AlertTriangle aria-hidden="true" />
              <span>{approvalProblem}</span>
            </div>
          ) : null}
        </section>
      ) : null}
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


export function ApprovalOutputDialog({
  approval,
  onApprovalStarted,
  onClose,
}: Readonly<{
  approval: ApprovalCandidate;
  onApprovalStarted?: (targetId: string) => void;
  onClose: () => void;
}>) {
  if (!approval.outputTargetId) return null;
  return (
    <div className="modal-backdrop output-review-backdrop">
      <section
        aria-label={`Review output for ${approval.nodeLabel}`}
        aria-modal="true"
        className="confirmation-dialog output-review-dialog"
        role="dialog"
      >
        <OutputPanel
          approval={approval}
          onApprovalStarted={onApprovalStarted}
          onClose={onClose}
          targetId={approval.outputTargetId}
        />
      </section>
    </div>
  );
}
