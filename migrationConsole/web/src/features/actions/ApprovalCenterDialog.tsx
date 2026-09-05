import {
  AlertTriangle,
  CheckCircle2,
  Clock3,
  FileOutput,
  LoaderCircle,
  ShieldCheck,
  X,
} from "lucide-react";

import type {
  ApprovalGateInventory,
  ApprovalGateSummary,
} from "../../api/client";
import { useEscapeCancel } from "../../hooks/useEscapeCancel";


const STATE_LABELS: Record<ApprovalGateSummary["state"], string> = {
  accepted: "Approval accepted",
  blocking: "Blocking now",
  error: "Error",
  "not-reached": "Not reached",
  "not-required": "Not required",
  passed: "Passed",
  preapproved: "Preapproved",
  "recovery-standby": "Recovery standby",
  upcoming: "Upcoming",
};


function ApprovalToggle({
  gate,
  onToggle,
  pending,
}: Readonly<{
  gate: ApprovalGateSummary;
  onToggle: (gate: ApprovalGateSummary, preapproved: boolean) => void;
  pending: boolean;
}>) {
  const disabled = !gate.toggleable || pending;
  return (
    <button
      aria-checked={gate.approved}
      aria-label={`Preapprove ${gate.stage}`}
      className={[
        "approval-toggle",
        gate.approved ? "active" : "",
      ].filter(Boolean).join(" ")}
      disabled={disabled}
      onClick={() => onToggle(gate, !gate.approved)}
      role="switch"
      title={gate.disabledReason ?? `Preapprove ${gate.stage}`}
      type="button"
    >
      <span aria-hidden="true">
        {pending ? <LoaderCircle className="spin" /> : null}
      </span>
      <b>{gate.approved ? "On" : "Off"}</b>
    </button>
  );
}


function ApprovalGateRow({
  gate,
  onApprove,
  onToggle,
  onViewOutput,
  pending,
}: Readonly<{
  gate: ApprovalGateSummary;
  onApprove: (gate: ApprovalGateSummary) => void;
  onToggle: (gate: ApprovalGateSummary, preapproved: boolean) => void;
  onViewOutput: (gate: ApprovalGateSummary) => void;
  pending: boolean;
}>) {
  const blocking = gate.state === "blocking";
  const Icon = blocking || gate.state === "error"
    ? AlertTriangle
    : gate.state === "passed"
      ? CheckCircle2
      : Clock3;
  return (
    <article className={`approval-center-row state-${gate.state}`}>
      <Icon aria-hidden="true" />
      <div className="approval-center-row-content">
        <header>
          <div>
            <strong>{gate.resourceName ?? "Workflow"}</strong>
            <span>{gate.resourceKind ?? "Migration workflow"}</span>
          </div>
          <div>
            <b>{gate.stage}</b>
            <small>{STATE_LABELS[gate.state]}</small>
          </div>
        </header>
        <p>{gate.effect}</p>
        {gate.reason ? (
          <p className="approval-center-reason">{gate.reason}</p>
        ) : null}
      </div>
      <div className="approval-center-row-actions">
        {blocking && gate.outputTargetId ? (
          <button
            onClick={() => onViewOutput(gate)}
            type="button"
          >
            <FileOutput aria-hidden="true" />
            View output
          </button>
        ) : null}
        {blocking && gate.approvalTargetId ? (
          <button
            aria-label={`Approve ${gate.stage}`}
            className="primary-button"
            disabled={pending}
            onClick={() => onApprove(gate)}
            type="button"
          >
            {pending
              ? <LoaderCircle className="spin" aria-hidden="true" />
              : <ShieldCheck aria-hidden="true" />}
            {pending ? "Approving" : "Approve"}
          </button>
        ) : gate.category === "checkpoint"
          && ["upcoming", "preapproved", "not-required"].includes(gate.state)
          ? (
            <ApprovalToggle
              gate={gate}
              onToggle={onToggle}
              pending={pending}
            />
            )
          : null}
      </div>
    </article>
  );
}


function BulkPreapprovalToggle({
  gates,
  onToggle,
  pendingNames,
}: Readonly<{
  gates: ApprovalGateSummary[];
  onToggle: (gates: ApprovalGateSummary[], preapproved: boolean) => void;
  pendingNames: Set<string>;
}>) {
  const toggleable = gates.filter((gate) => gate.toggleable);
  if (toggleable.length === 0) return null;
  const approvedCount = toggleable.filter((gate) => gate.approved).length;
  const checked = approvedCount === toggleable.length;
  const mixed = approvedCount > 0 && !checked;
  const pending = toggleable.some((gate) => pendingNames.has(gate.name));
  return (
    <div className="approval-center-bulk-toggle">
      <strong>Preapprove all</strong>
      <button
        aria-checked={mixed ? "mixed" : checked}
        aria-label="Preapprove all upcoming checkpoints"
        className={[
          "approval-toggle",
          checked ? "active" : "",
          mixed ? "mixed" : "",
        ].filter(Boolean).join(" ")}
        disabled={pending}
        onClick={() => onToggle(toggleable, !checked)}
        role="switch"
        title="Preapprove every upcoming checkpoint"
        type="button"
      >
        <span aria-hidden="true">
          {pending ? <LoaderCircle className="spin" /> : null}
        </span>
        <b>{mixed ? "Some" : checked ? "On" : "Off"}</b>
      </button>
    </div>
  );
}


function ApprovalSection({
  gates,
  label,
  showBulkToggle = false,
  ...rowProps
}: Readonly<{
  gates: ApprovalGateSummary[];
  label: string;
  onApprove: (gate: ApprovalGateSummary) => void;
  onToggle: (gate: ApprovalGateSummary, preapproved: boolean) => void;
  onToggleAll: (
    gates: ApprovalGateSummary[],
    preapproved: boolean,
  ) => void;
  onViewOutput: (gate: ApprovalGateSummary) => void;
  pendingNames: Set<string>;
  showBulkToggle?: boolean;
}>) {
  if (gates.length === 0) return null;
  return (
    <section className="approval-center-section">
      <header>
        <h3>{label}</h3>
        <span>{gates.length}</span>
        {showBulkToggle ? (
          <BulkPreapprovalToggle
            gates={gates}
            onToggle={rowProps.onToggleAll}
            pendingNames={rowProps.pendingNames}
          />
        ) : null}
      </header>
      <div>
        {gates.map((gate) => (
          <ApprovalGateRow
            gate={gate}
            key={gate.name}
            onApprove={rowProps.onApprove}
            onToggle={rowProps.onToggle}
            onViewOutput={rowProps.onViewOutput}
            pending={rowProps.pendingNames.has(gate.name)}
          />
        ))}
      </div>
    </section>
  );
}


export function ApprovalCenterDialog({
  error,
  inventory,
  loading,
  onClose,
  onApprove,
  onToggle,
  onToggleAll,
  onViewOutput,
  pendingNames,
}: Readonly<{
  error: string | null;
  inventory: ApprovalGateInventory | undefined;
  loading: boolean;
  onClose: () => void;
  onApprove: (gate: ApprovalGateSummary) => void;
  onToggle: (gate: ApprovalGateSummary, preapproved: boolean) => void;
  onToggleAll: (
    gates: ApprovalGateSummary[],
    preapproved: boolean,
  ) => void;
  onViewOutput: (gate: ApprovalGateSummary) => void;
  pendingNames: Set<string>;
}>) {
  const dialogRef = useEscapeCancel<HTMLElement>(onClose);
  const visible = (inventory?.gates ?? []).filter((gate) => (
    gate.category === "checkpoint"
    || !["recovery-standby", "not-required"].includes(gate.state)
  ));
  const blocking = visible.filter((gate) => (
    ["blocking", "accepted", "error"].includes(gate.state)
  ));
  const upcoming = visible.filter((gate) => (
    ["upcoming", "preapproved", "not-required"].includes(gate.state)
  ));
  const completed = visible.filter((gate) => (
    ["passed", "not-reached"].includes(gate.state)
  ));
  return (
    <div className="modal-backdrop">
      <section
        aria-labelledby="approval-center-title"
        aria-modal="true"
        className="confirmation-dialog approval-center-dialog"
        data-escape-cancel-layer
        ref={dialogRef}
        role="dialog"
      >
        <header>
          <ShieldCheck aria-hidden="true" />
          <div>
            <span>Workflow checkpoints</span>
            <h2 id="approval-center-title">Approvals</h2>
            <small>
              {blocking.length} blocking, {upcoming.length} upcoming
            </small>
          </div>
          <button
            aria-label="Close approvals"
            className="icon-button"
            onClick={onClose}
            type="button"
          >
            <X aria-hidden="true" />
          </button>
        </header>
        {loading ? (
          <div className="approval-center-state" role="status">
            <LoaderCircle className="spin" aria-hidden="true" />
            Loading approval checkpoints
          </div>
        ) : error ? (
          <div className="approval-center-state error" role="alert">
            <AlertTriangle aria-hidden="true" />
            {error}
          </div>
        ) : visible.length === 0 ? (
          <div className="approval-center-state">
            No approval checkpoints are defined for this workflow.
          </div>
        ) : (
          <div className="approval-center-sections">
            <ApprovalSection
              gates={blocking}
              label="Blocking now"
              onApprove={onApprove}
              onToggle={onToggle}
              onToggleAll={onToggleAll}
              onViewOutput={onViewOutput}
              pendingNames={pendingNames}
            />
            <ApprovalSection
              gates={upcoming}
              label="Upcoming"
              onApprove={onApprove}
              onToggleAll={onToggleAll}
              onToggle={onToggle}
              onViewOutput={onViewOutput}
              pendingNames={pendingNames}
              showBulkToggle
            />
            <ApprovalSection
              gates={completed}
              label="Passed"
              onApprove={onApprove}
              onToggle={onToggle}
              onToggleAll={onToggleAll}
              onViewOutput={onViewOutput}
              pendingNames={pendingNames}
            />
          </div>
        )}
      </section>
    </div>
  );
}
