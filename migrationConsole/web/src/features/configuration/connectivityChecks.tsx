import {
  useEffect,
  useMemo,
  useState,
} from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  CheckCircle2,
  CircleDashed,
  ExternalLink,
  LoaderCircle,
  Network,
  RefreshCw,
  TriangleAlert,
} from "lucide-react";

import {
  getConnectivityInventory,
  getOperations,
  startConnectivityChecks,
  type ConnectivityInventory,
  type ConnectivityTarget,
  type Operation,
} from "../../api/client";
import { ModalDialog } from "../../components/ModalDialog";
import type { BrowserConfigDraft } from "./browserDraft";


export type ConnectivityStatus =
  | "valid"
  | "partially_verified"
  | "failed"
  | "checking"
  | "stale"
  | "not_checked"
  | "not_applicable";


export interface ConnectivityStage {
  id: string;
  label: string;
  status: string;
  message: string;
  code?: string;
  httpStatus?: number;
}


export interface ConnectivityCheck {
  targetId: string;
  kind: ConnectivityTarget["kind"];
  refName: string;
  label: string;
  editPath: string[];
  checkedAt: string;
  status: ConnectivityStatus;
  summary: string;
  stages: ConnectivityStage[];
  logs?: string;
  executionContext?: {
    namespace?: string;
    serviceAccount?: string;
    workload?: string;
    principal?: string;
  };
}


export interface ConnectivityTargetState {
  target: ConnectivityTarget;
  status: ConnectivityStatus;
  check?: ConnectivityCheck;
}


export type ConnectivityNavigationStates = Record<string, ConnectivityStatus>;


interface ConnectivityOperationResult {
  configNonce: string;
  status: ConnectivityStatus;
  summary: string;
  checks: ConnectivityCheck[];
}


const ACTIVE_STATUSES = new Set(["queued", "running", "waiting"]);


function resultFrom(operation: Operation): ConnectivityOperationResult | null {
  if (operation.kind !== "connectivity-check") return null;
  const result = operation.result as Partial<ConnectivityOperationResult>;
  if (
    typeof result.configNonce !== "string"
    || !Array.isArray(result.checks)
  ) {
    return null;
  }
  return result as ConnectivityOperationResult;
}


function statusLabel(status: ConnectivityStatus): string {
  switch (status) {
    case "valid": return "Valid";
    case "partially_verified": return "Partially verified";
    case "failed": return "Failed";
    case "checking": return "Checking";
    case "stale": return "Recheck needed";
    case "not_applicable": return "Not applicable";
    default: return "Not checked";
  }
}


function overallStatus(
  states: ConnectivityTargetState[],
): ConnectivityStatus {
  const statuses = new Set(states.map((state) => state.status));
  if (statuses.has("failed")) return "failed";
  if (statuses.has("checking")) return "checking";
  if (statuses.has("stale")) return "stale";
  if (statuses.has("not_checked")) return "not_checked";
  if (statuses.has("partially_verified")) return "partially_verified";
  if (statuses.has("valid")) return "valid";
  if (states.length > 0 && statuses.has("not_applicable")) {
    return "not_applicable";
  }
  return "not_checked";
}


function overallSummary(status: ConnectivityStatus): string {
  switch (status) {
    case "valid":
      return "All applicable configured connections passed their checks.";
    case "partially_verified":
      return "All attempted checks passed, but at least one could not be fully verified.";
    case "failed":
      return "At least one configured connection failed its check.";
    case "checking":
      return "One or more connectivity checks are still running.";
    case "stale":
      return "Configured values changed after at least one check completed.";
    case "not_applicable":
      return "No configured connection currently has an applicable direct check.";
    default:
      return "Run the checks to verify the configured connections.";
  }
}


function statusIcon(status: ConnectivityStatus) {
  if (status === "checking") {
    return <LoaderCircle className="spin" aria-hidden="true" />;
  }
  if (status === "valid" || status === "not_applicable") {
    return <CheckCircle2 aria-hidden="true" />;
  }
  if (status === "failed") {
    return <TriangleAlert aria-hidden="true" />;
  }
  return <CircleDashed aria-hidden="true" />;
}


function editTargetId(path: string[]): string {
  return `edit:${path.join(".")}`;
}


function targetForPath(
  targets: ConnectivityTarget[],
  path: string[] | null,
): ConnectivityTarget | null {
  if (!path) return null;
  const matches = targets.filter((target) => (
    target.editPath.every((part, index) => path[index] === part)
    || path.every((part, index) => target.editPath[index] === part)
  ));
  return matches.sort(
    (left, right) => right.editPath.length - left.editPath.length,
  )[0] ?? null;
}


// The hook and its compact renderers intentionally share the result contract.
// eslint-disable-next-line react-refresh/only-export-components
export function useConnectivityChecks(
  draft: BrowserConfigDraft | undefined,
  scopePath: string[] | null,
  debounceMs = 350,
) {
  const queryClient = useQueryClient();
  const nonce = draft?.draftRevision ?? null;
  const rawYaml = draft?.rawDocument ?? "";
  const [request, setRequest] = useState<{
    nonce: string;
    rawYaml: string;
  } | null>(null);
  const [inventory, setInventory] = useState<ConnectivityInventory | null>(null);
  const [inventoryProblem, setInventoryProblem] = useState("");
  const [operationProblem, setOperationProblem] = useState("");
  const [inventoryLoading, setInventoryLoading] = useState(false);
  const [started, setStarted] = useState<Array<{
    operationId: string;
    nonce: string;
  }>>([]);

  useEffect(() => {
    if (!nonce || !rawYaml || draft?.rawYaml !== undefined) {
      setRequest(null);
      setInventory(null);
      setInventoryProblem("");
      setInventoryLoading(false);
      return;
    }
    setInventoryLoading(true);
    const timer = globalThis.setTimeout(() => {
      setRequest({ nonce, rawYaml });
    }, debounceMs);
    return () => globalThis.clearTimeout(timer);
  }, [debounceMs, draft?.rawYaml, nonce, rawYaml]);

  useEffect(() => {
    if (!request) return;
    const controller = new AbortController();
    setInventoryLoading(true);
    setInventoryProblem("");
    void getConnectivityInventory(
      request.rawYaml,
      request.nonce,
      controller.signal,
    ).then((result) => {
      if (result.configNonce !== request.nonce) return;
      setInventory(result);
    }).catch((error: unknown) => {
      if (controller.signal.aborted) return;
      setInventory(null);
      setInventoryProblem(error instanceof Error ? error.message : String(error));
    }).finally(() => {
      if (!controller.signal.aborted) setInventoryLoading(false);
    });
    return () => controller.abort();
  }, [request]);

  const operations = useQuery({
    queryKey: ["operations"],
    queryFn: getOperations,
  });
  const operationResults = useMemo(
    () => (operations.data ?? [])
      .map((operation) => ({ operation, result: resultFrom(operation) }))
      .filter((
        item,
      ): item is { operation: Operation; result: ConnectivityOperationResult } => (
        item.result !== null
      )),
    [operations.data],
  );
  const currentById = useMemo(() => {
    const checks = new Map<string, ConnectivityCheck>();
    operationResults.forEach(({ result }) => {
      if (result.configNonce !== nonce) return;
      result.checks.forEach((check) => {
        if (!checks.has(check.targetId)) checks.set(check.targetId, check);
      });
    });
    return checks;
  }, [nonce, operationResults]);
  const priorById = useMemo(() => {
    const checks = new Map<string, ConnectivityCheck>();
    operationResults.forEach(({ result }) => {
      if (result.configNonce === nonce) return;
      result.checks.forEach((check) => {
        if (!checks.has(check.targetId)) checks.set(check.targetId, check);
      });
    });
    return checks;
  }, [nonce, operationResults]);
  const activeTargetIds = useMemo(() => {
    const operationsById = new Map(
      (operations.data ?? []).map((operation) => [operation.id, operation]),
    );
    return new Set(
      started
        .filter((record) => record.nonce === nonce)
        .map((record) => operationsById.get(record.operationId))
        .filter((operation): operation is Operation => (
          operation !== undefined && ACTIVE_STATUSES.has(operation.status)
        ))
        .flatMap((operation) => operation.targetIds),
    );
  }, [nonce, operations.data, started]);
  const targets = useMemo(
    () => inventory?.targets ?? [],
    [inventory?.targets],
  );
  const states = useMemo(() => {
    return targets.map((target): ConnectivityTargetState => {
      if (activeTargetIds.has(target.id)) {
        return { target, status: "checking" };
      }
      const current = currentById.get(target.id);
      if (current) return { target, status: current.status, check: current };
      const prior = priorById.get(target.id);
      if (prior) return { target, status: "stale", check: prior };
      return { target, status: "not_checked" };
    });
  }, [activeTargetIds, currentById, priorById, targets]);
  const navigationStates = useMemo(
    () => Object.fromEntries(
      states.map(({ target, status }) => [editTargetId(target.editPath), status]),
    ),
    [states],
  );
  const selectedTarget = targetForPath(targets, scopePath);
  const selectedState = selectedTarget
    ? states.find(({ target }) => target.id === selectedTarget.id) ?? null
    : null;

  const start = async (targetIds: string[] = []) => {
    if (!nonce || !rawYaml) return;
    setOperationProblem("");
    try {
      const operation = await startConnectivityChecks(
        rawYaml,
        nonce,
        targetIds,
      );
      setStarted((current) => [
        ...current.filter((record) => {
          if (record.nonce !== nonce) return false;
          const prior = (operations.data ?? []).find(
            (candidate) => candidate.id === record.operationId,
          );
          return prior !== undefined && ACTIVE_STATUSES.has(prior.status);
        }),
        { operationId: operation.id, nonce },
      ]);
      queryClient.setQueryData<Operation[]>(
        ["operations"],
        (current = []) => [
          operation,
          ...current.filter((candidate) => candidate.id !== operation.id),
        ],
      );
      await queryClient.invalidateQueries({ queryKey: ["operations"] });
    } catch (error) {
      setOperationProblem(error instanceof Error ? error.message : String(error));
    }
  };

  return {
    inventoryLoading,
    inventoryProblem: operationProblem || inventoryProblem,
    navigationStates,
    selectedState,
    start,
    states,
  };
}


function openLogs(check: ConnectivityCheck) {
  const key = `connectivity-logs:${crypto.randomUUID()}`;
  globalThis.sessionStorage.setItem(key, JSON.stringify(check));
  globalThis.open(
    `/connectivity-logs?key=${encodeURIComponent(key)}`,
    "_blank",
    "noopener,noreferrer",
  );
}


function CheckDetails({ state }: Readonly<{ state: ConnectivityTargetState }>) {
  const check = state.check;
  if (!check) {
    return (
      <p>
        {state.status === "checking"
          ? "The check is running in the configured execution context."
          : "Run this check to verify the current saved or unsaved values."}
      </p>
    );
  }
  const showContext = state.status === "failed";
  return (
    <>
      <p>{check.summary}</p>
      {check.stages.length > 0 ? (
        <ol className="connectivity-stage-list">
          {check.stages.map((stage) => (
            <li className={`status-${stage.status}`} key={stage.id}>
              <strong>{stage.label}</strong>
              <span>{stage.message}</span>
            </li>
          ))}
        </ol>
      ) : null}
      {showContext && check.executionContext ? (
        <dl className="connectivity-context">
          {check.executionContext.principal ? (
            <>
              <dt>AWS principal</dt>
              <dd>{check.executionContext.principal}</dd>
            </>
          ) : null}
          {check.executionContext.namespace ? (
            <>
              <dt>Namespace</dt>
              <dd>{check.executionContext.namespace}</dd>
            </>
          ) : null}
          {check.executionContext.serviceAccount ? (
            <>
              <dt>Service account</dt>
              <dd>{check.executionContext.serviceAccount}</dd>
            </>
          ) : null}
        </dl>
      ) : null}
      {check.logs ? (
        <details
          className="connectivity-log-details"
          open={state.status === "failed"}
        >
          <summary>Probe logs</summary>
          <div className="connectivity-log-heading">
            <span>Full check output</span>
            <button onClick={() => openLogs(check)} type="button">
              <ExternalLink aria-hidden="true" />
              Open in new tab
            </button>
          </div>
          <pre>{check.logs}</pre>
        </details>
      ) : null}
    </>
  );
}


export function ConnectivityPanel({
  state,
  onCheck,
}: Readonly<{
  state: ConnectivityTargetState;
  onCheck: () => void;
}>) {
  return (
    <section
      className={`connectivity-panel status-${state.status}`}
      aria-label={`${state.target.label} connectivity`}
    >
      <header>
        {statusIcon(state.status)}
        <div>
          <strong>Connectivity</strong>
          <span>{statusLabel(state.status)}</span>
        </div>
        <button
          disabled={state.status === "checking"}
          onClick={onCheck}
          type="button"
        >
          <RefreshCw aria-hidden="true" />
          {state.check ? "Recheck" : "Check"}
        </button>
      </header>
      <CheckDetails state={state} />
    </section>
  );
}


export function ConnectivityDialog({
  loading,
  onCheck,
  onClose,
  problem,
  states,
}: Readonly<{
  loading: boolean;
  onCheck: (targetIds?: string[]) => void;
  onClose: () => void;
  problem: string;
  states: ConnectivityTargetState[];
}>) {
  const checking = states.some((state) => state.status === "checking");
  const aggregateStatus = overallStatus(states);
  return (
    <ModalDialog
      className="connectivity-dialog"
      icon={<Network aria-hidden="true" />}
      kicker="Configuration diagnostics"
      onClose={onClose}
      portal
      title="Connectivity checks"
      subtitle="Checks run asynchronously and do not block configuration editing."
      footer={(
        <>
          <button onClick={onClose} type="button">Close</button>
          <button
            className="primary-button"
            disabled={loading || checking || states.length === 0}
            onClick={() => onCheck()}
            type="button"
          >
            <RefreshCw aria-hidden="true" />
            Recheck all
          </button>
        </>
      )}
    >
      {problem ? (
        <div className="dialog-error" role="alert">
          <TriangleAlert aria-hidden="true" />
          <span>{problem}</span>
        </div>
      ) : null}
      {loading ? (
        <div className="dialog-loading" role="status">
          <LoaderCircle className="spin" aria-hidden="true" />
          Loading configured connections
        </div>
      ) : (
        <div className="connectivity-list">
          {states.length > 0 ? (
            <section
              aria-label="Overall connectivity"
              className={[
                "connectivity-overall",
                `status-${aggregateStatus}`,
              ].join(" ")}
            >
              {statusIcon(aggregateStatus)}
              <div>
                <strong>Configuration connectivity</strong>
                <span>{statusLabel(aggregateStatus)}</span>
                <p>{overallSummary(aggregateStatus)}</p>
              </div>
            </section>
          ) : null}
          {states.map((state) => (
            <article
              className={`connectivity-list-item status-${state.status}`}
              key={state.target.id}
            >
              <header>
                {statusIcon(state.status)}
                <div>
                  <strong>{state.target.label}</strong>
                  <span>{statusLabel(state.status)}</span>
                </div>
                <button
                  disabled={state.status === "checking"}
                  onClick={() => onCheck([state.target.id])}
                  type="button"
                >
                  <RefreshCw aria-hidden="true" />
                  {state.check ? "Recheck" : "Check"}
                </button>
              </header>
              {state.status === "failed"
                || state.status === "partially_verified" ? (
                <CheckDetails state={state} />
              ) : null}
            </article>
          ))}
          {states.length === 0 && !problem ? (
            <p className="connectivity-empty">
              No source, target, or repository checks are configured.
            </p>
          ) : null}
        </div>
      )}
    </ModalDialog>
  );
}


export function StandaloneConnectivityLogs() {
  const params = new URLSearchParams(globalThis.location.search);
  const key = params.get("key") ?? "";
  const raw = key ? globalThis.sessionStorage.getItem(key) : null;
  let check: ConnectivityCheck | null = null;
  try {
    check = raw ? JSON.parse(raw) as ConnectivityCheck : null;
  } catch {
    check = null;
  }
  return (
    <main className="standalone-connectivity-logs">
      <header>
        <Network aria-hidden="true" />
        <div>
          <span>Connectivity probe logs</span>
          <h1>{check?.label ?? "Connectivity check"}</h1>
        </div>
      </header>
      {check ? (
        <>
          <p>{check.summary}</p>
          <pre>{check.logs || "No probe output was captured."}</pre>
        </>
      ) : (
        <p>These logs are no longer available in this browser session.</p>
      )}
    </main>
  );
}
