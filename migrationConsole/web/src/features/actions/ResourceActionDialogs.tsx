import { useEffect, useState } from "react";
import {
  useQueries,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import {
  AlertTriangle,
  FileOutput,
  LoaderCircle,
  Pencil,
  RotateCcw,
  ShieldCheck,
  X,
} from "lucide-react";

import {
  approveTarget,
  executeReset,
  getApprovalReview,
  getCombinedResetPlan,
  getResetPlan,
} from "../../api/client";
import { useEscapeCancel } from "../../hooks/useEscapeCancel";
import type { ApprovalCandidate } from "./approvals";


function DialogError({
  error,
  retry,
}: Readonly<{
  error: Error;
  retry: () => void;
}>) {
  return (
    <div className="action-dialog-error" role="alert">
      <AlertTriangle aria-hidden="true" />
      <span>{error.message}</span>
      <button onClick={retry} type="button">Retry</button>
    </div>
  );
}


export function ApprovalDialog({
  candidates,
  initialTargetId,
  onClose,
  onEdit,
  onStarted,
  onViewOutput,
}: Readonly<{
  candidates: ApprovalCandidate[];
  initialTargetId: string;
  onClose: () => void;
  onEdit: (candidate: ApprovalCandidate) => void;
  onStarted?: (targetId: string) => void;
  onViewOutput: (candidate: ApprovalCandidate) => void;
}>) {
  const queryClient = useQueryClient();
  const [submitting, setSubmitting] = useState<Set<string>>(new Set());
  const [processing, setProcessing] = useState<Set<string>>(new Set());
  const [problems, setProblems] = useState<Record<string, string>>({});
  const reviews = useQueries({
    queries: candidates.map((candidate) => ({
      queryKey: ["approval-review", candidate.targetId],
      queryFn: () => getApprovalReview(candidate.targetId),
      retry: false,
    })),
  });
  const resetCandidates = candidates.filter(
    (candidate) => candidate.immutable && candidate.resetTargetId,
  );
  const resetTargetIds = resetCandidates.map(
    (candidate) => candidate.resetTargetId as string,
  );
  const combinedPlan = useQuery({
    queryKey: ["reset-plan", "combined", ...resetTargetIds],
    queryFn: () => getCombinedResetPlan(resetTargetIds),
    enabled: resetTargetIds.length > 0,
    retry: false,
  });
  const dialogRef = useEscapeCancel<HTMLElement>(onClose);
  useEffect(() => {
    if (processing.size === 0) return;
    const timer = globalThis.setInterval(() => {
      void queryClient.invalidateQueries({ queryKey: ["manage-state"] });
      void queryClient.invalidateQueries({ queryKey: ["operations"] });
    }, 2000);
    return () => globalThis.clearInterval(timer);
  }, [processing.size, queryClient]);
  const reviewFor = (targetId: string) => (
    reviews[candidates.findIndex(
      (candidate) => candidate.targetId === targetId,
    )]
  );
  const setTargetsSubmitting = (
    targetIds: string[],
    active: boolean,
  ) => {
    setSubmitting((current) => {
      const next = new Set(current);
      targetIds.forEach((targetId) => (
        active ? next.add(targetId) : next.delete(targetId)
      ));
      return next;
    });
  };
  const clearProblems = (targetIds: string[]) => {
    setProblems((current) => {
      const next = { ...current };
      targetIds.forEach((targetId) => delete next[targetId]);
      return next;
    });
  };
  const finishStarted = async (targetIds: string[]) => {
    setProcessing((current) => new Set([...current, ...targetIds]));
    targetIds.forEach((targetId) => onStarted?.(targetId));
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ["operations"] }),
      queryClient.invalidateQueries({ queryKey: ["manage-state"] }),
    ]);
  };
  const approve = async (candidate: ApprovalCandidate) => {
    const review = reviewFor(candidate.targetId)?.data;
    if (!review) return;
    setTargetsSubmitting([candidate.targetId], true);
    clearProblems([candidate.targetId]);
    try {
      await approveTarget(candidate.targetId, review.gateRevision);
      await finishStarted([candidate.targetId]);
    } catch (error) {
      setProblems((current) => ({
        ...current,
        [candidate.targetId]: (
          error instanceof Error ? error.message : String(error)
        ),
      }));
    } finally {
      setTargetsSubmitting([candidate.targetId], false);
    }
  };
  const resetAndResubmit = async (
    selectedCandidates: ApprovalCandidate[],
    planToken?: string,
  ) => {
    const targetIds = selectedCandidates.map(
      (candidate) => candidate.targetId,
    );
    const resetIds = selectedCandidates.flatMap(
      (candidate) => candidate.resetTargetId
        ? [candidate.resetTargetId]
        : [],
    );
    if (resetIds.length !== selectedCandidates.length) {
      return;
    }
    setTargetsSubmitting(targetIds, true);
    clearProblems(targetIds);
    try {
      let plan;
      if (planToken) {
        plan = { token: planToken };
      } else if (resetIds.length === 1) {
        plan = await getResetPlan(resetIds[0]);
      } else {
        plan = await getCombinedResetPlan(resetIds);
      }
      await executeReset(plan.token, { resubmit: true });
      await finishStarted(targetIds);
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      setProblems((current) => ({
        ...current,
        ...Object.fromEntries(targetIds.map((targetId) => [
          targetId,
          message,
        ])),
      }));
      void combinedPlan.refetch();
    } finally {
      setTargetsSubmitting(targetIds, false);
    }
  };
  return (
    <div className="modal-backdrop">
      <section
        aria-labelledby="approval-dialog-title"
        aria-modal="true"
        className="confirmation-dialog action-review-dialog approval-list-dialog"
        data-escape-cancel-layer
        ref={dialogRef}
        role="dialog"
      >
        <header>
          <ShieldCheck aria-hidden="true" />
          <div>
            <span>Workflow intervention</span>
            <h2 id="approval-dialog-title">Review required actions</h2>
            <small>{candidates.length} waiting {
              candidates.length === 1 ? "gate" : "gates"
            }</small>
          </div>
          <button
            aria-label="Close required actions"
            className="icon-button"
            onClick={onClose}
            type="button"
          >
            <X aria-hidden="true" />
          </button>
        </header>
        <div className="approval-review-list">
          {candidates.map((candidate) => {
            const review = reviewFor(candidate.targetId);
            const busy = submitting.has(candidate.targetId);
            const waiting = processing.has(candidate.targetId);
            const impossible = candidate.immutable;
            const resetRequired = impossible && candidate.resetTargetId;
            return (
              <article
                className={[
                  "approval-review-item",
                  impossible ? "impossible-change" : "",
                  candidate.targetId === initialTargetId ? "initial" : "",
                ].filter(Boolean).join(" ")}
                key={candidate.targetId}
              >
                <header>
                  {waiting
                    ? <LoaderCircle className="spin" aria-hidden="true" />
                    : impossible
                      ? <AlertTriangle aria-hidden="true" />
                      : <ShieldCheck aria-hidden="true" />}
                  <div>
                    <span>
                      {waiting
                        ? "Action in progress"
                        : resetRequired
                          ? "Impossible update / reset required"
                          : impossible
                            ? "Impossible update / resource absent"
                            : "Approval required"}
                    </span>
                    <h3>
                      {review?.data?.resourceName ?? candidate.nodeLabel}
                    </h3>
                  </div>
                  {!waiting && review?.data ? (
                    <strong>{review.data.stage}</strong>
                  ) : null}
                </header>
                {!waiting && review?.isPending ? (
                  <div className="action-review-loading" role="status">
                    <LoaderCircle className="spin" aria-hidden="true" />
                    Resolving the exact gate
                  </div>
                ) : !waiting && review?.isError ? (
                  <DialogError
                    error={review.error}
                    retry={() => void review.refetch()}
                  />
                ) : !waiting && review?.data ? (
                  <>
                    <p className="approval-item-effect">
                      {review.data.effect}
                    </p>
                    {candidate.immutableReason ?? review.data.reason ? (
                      <div className="action-warning">
                        <AlertTriangle aria-hidden="true" />
                        <span>
                          {candidate.immutableReason ?? review.data.reason}
                        </span>
                      </div>
                    ) : null}
                    {resetRequired ? (
                      <p className="approval-remedy">
                        The deployed resource must be deleted before this
                        configuration can be applied. Reset and resubmit
                        performs both steps as one tracked operation.
                      </p>
                    ) : impossible ? (
                      <p className="approval-remedy">
                        The resource is absent. A replacement workflow is
                        required to recreate it.
                      </p>
                    ) : null}
                    <small
                      className="approval-gate-name"
                      title={review.data.gateName}
                    >
                      Gate: {review.data.gateName}
                    </small>
                  </>
                ) : null}
                {waiting ? (
                  <div className="approval-processing" role="status">
                    <LoaderCircle className="spin" aria-hidden="true" />
                    <span>
                      Action accepted. Waiting for workflow reconciliation.
                    </span>
                  </div>
                ) : null}
                {problems[candidate.targetId] ? (
                  <p className="action-inline-error">
                    {problems[candidate.targetId]}
                  </p>
                ) : null}
                {!waiting ? (
                  <footer>
                    {candidate.outputTargetId ? (
                      <button
                        disabled={busy}
                        onClick={() => onViewOutput(candidate)}
                        type="button"
                      >
                        <FileOutput aria-hidden="true" />
                        View output
                      </button>
                    ) : null}
                    {candidate.editTargetId ? (
                      <button
                        disabled={busy}
                        onClick={() => onEdit(candidate)}
                        type="button"
                      >
                        <Pencil aria-hidden="true" />
                        Edit configuration
                      </button>
                    ) : null}
                    {resetRequired ? (
                      <button
                        className="danger-confirm"
                        disabled={busy}
                        onClick={() => void resetAndResubmit([candidate])}
                        type="button"
                      >
                        {busy
                          ? <LoaderCircle className="spin" aria-hidden="true" />
                          : <RotateCcw aria-hidden="true" />}
                        Reset &amp; resubmit
                      </button>
                    ) : !impossible ? (
                      <button
                        className="primary-button"
                        disabled={
                          busy
                          || !review?.data
                          || Boolean(candidate.disabledReason)
                        }
                        onClick={() => void approve(candidate)}
                        title={candidate.disabledReason ?? undefined}
                        type="button"
                      >
                        {busy
                          ? <LoaderCircle className="spin" aria-hidden="true" />
                          : <ShieldCheck aria-hidden="true" />}
                        Approve
                      </button>
                    ) : null}
                  </footer>
                ) : null}
              </article>
            );
          })}
        </div>
        {resetCandidates.length > 0 ? (
          <section className="combined-reset-review">
            <header>
              <div>
                <strong>Combined reset plan</strong>
                <span>
                  Review and resubmit all impossible deployed updates together.
                </span>
              </div>
              {combinedPlan.data ? (
                <small>
                  {combinedPlan.data.targets.length} {
                    combinedPlan.data.targets.length === 1
                      ? "resource"
                      : "resources"
                  } removed
                </small>
              ) : null}
            </header>
            {combinedPlan.isPending ? (
              <div className="action-review-loading" role="status">
                <LoaderCircle className="spin" aria-hidden="true" />
                Building the dependency-safe reset plan
              </div>
            ) : combinedPlan.isError ? (
              <DialogError
                error={combinedPlan.error}
                retry={() => void combinedPlan.refetch()}
              />
            ) : combinedPlan.data ? (
              <details>
                <summary>Show resources in deletion order</summary>
                <ol className="reset-target-list">
                  {combinedPlan.data.targets.map((target) => (
                    <li key={`${target.plural}-${target.name}`}>
                      <span>
                        <strong>{target.path}</strong>
                        <small>{target.phase}</small>
                      </span>
                      {target.dependsOn.length > 0 ? (
                        <span>Depends on {target.dependsOn.join(", ")}</span>
                      ) : null}
                    </li>
                  ))}
                </ol>
              </details>
            ) : null}
          </section>
        ) : null}
        <footer>
          <button onClick={onClose} type="button">Close</button>
          {resetCandidates.length > 0 ? (
            <button
              className="danger-confirm"
              disabled={
                combinedPlan.isPending
                || !combinedPlan.data
                || resetCandidates.some((candidate) => (
                  submitting.has(candidate.targetId)
                  || processing.has(candidate.targetId)
                ))
              }
              onClick={() => void resetAndResubmit(
                resetCandidates,
                combinedPlan.data?.token,
              )}
              type="button"
            >
              <RotateCcw aria-hidden="true" />
              Reset &amp; resubmit all ({resetCandidates.length})
            </button>
          ) : null}
        </footer>
      </section>
    </div>
  );
}


export function ResetDialog({
  targetId,
  onClose,
}: Readonly<{
  targetId: string;
  onClose: () => void;
}>) {
  const queryClient = useQueryClient();
  const [submitting, setSubmitting] = useState(false);
  const [problem, setProblem] = useState("");
  const plan = useQuery({
    queryKey: ["reset-plan", targetId],
    queryFn: () => getResetPlan(targetId),
    retry: false,
  });
  const dialogRef = useEscapeCancel<HTMLElement>(onClose, submitting);
  const reset = async () => {
    if (!plan.data) return;
    setSubmitting(true);
    setProblem("");
    try {
      await executeReset(plan.data.token);
      await queryClient.invalidateQueries({ queryKey: ["operations"] });
      onClose();
    } catch (error) {
      setProblem(error instanceof Error ? error.message : String(error));
      if (
        error instanceof Error
        && "status" in error
        && (error as { status?: number }).status === 409
      ) {
        void plan.refetch();
      }
    } finally {
      setSubmitting(false);
    }
  };
  return (
    <div className="modal-backdrop">
      <section
        aria-labelledby="reset-dialog-title"
        aria-modal="true"
        className="confirmation-dialog action-review-dialog reset-review-dialog"
        data-escape-cancel-layer
        ref={dialogRef}
        role="dialog"
      >
        <header>
          <RotateCcw aria-hidden="true" />
          <div>
            <span>Dependency-safe reset</span>
            <h2 id="reset-dialog-title">Review reset plan</h2>
          </div>
          <button
            aria-label="Close reset"
            className="icon-button"
            onClick={onClose}
            type="button"
          >
            <X aria-hidden="true" />
          </button>
        </header>
        {plan.isPending ? (
          <div className="action-review-loading" role="status">
            <LoaderCircle className="spin" aria-hidden="true" />
            Resolving dependencies and current versions
          </div>
        ) : plan.isError ? (
          <DialogError error={plan.error} retry={() => void plan.refetch()} />
        ) : plan.data ? (
          <>
            <p>
              This exact version-bound plan will delete the following
              resources in dependency-safe order.
            </p>
            <ol className="reset-target-list">
              {plan.data.targets.map((target) => (
                <li key={`${target.plural}-${target.name}`}>
                  <span>
                    <strong>{target.path}</strong>
                    <small>{target.phase}</small>
                  </span>
                  {target.dependsOn.length > 0 ? (
                    <span>Depends on {target.dependsOn.join(", ")}</span>
                  ) : null}
                </li>
              ))}
            </ol>
            {[...plan.data.messages, ...plan.data.warnings].map((warning) => (
              <div className="action-warning" key={warning}>
                <AlertTriangle aria-hidden="true" />
                <span>{warning}</span>
              </div>
            ))}
          </>
        ) : null}
        {problem ? <p className="action-inline-error">{problem}</p> : null}
        <footer>
          <button disabled={submitting} onClick={onClose} type="button">
            Cancel
          </button>
          <button
            className="danger-confirm"
            disabled={submitting || !plan.data}
            onClick={() => void reset()}
            type="button"
          >
            {submitting
              ? <LoaderCircle className="spin" aria-hidden="true" />
              : <RotateCcw aria-hidden="true" />}
            Reset exact plan
          </button>
        </footer>
      </section>
    </div>
  );
}
