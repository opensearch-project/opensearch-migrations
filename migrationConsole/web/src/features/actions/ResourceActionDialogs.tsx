import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  AlertTriangle,
  LoaderCircle,
  RotateCcw,
  ShieldCheck,
  X,
} from "lucide-react";

import {
  approveTarget,
  executeReset,
  getApprovalReview,
  getResetPlan,
} from "../../api/client";


function DialogError({
  error,
  retry,
}: {
  error: Error;
  retry: () => void;
}) {
  return (
    <div className="action-dialog-error" role="alert">
      <AlertTriangle aria-hidden="true" />
      <span>{error.message}</span>
      <button onClick={retry} type="button">Retry</button>
    </div>
  );
}


export function ApprovalDialog({
  targetId,
  onClose,
}: {
  targetId: string;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const [submitting, setSubmitting] = useState(false);
  const [problem, setProblem] = useState("");
  const review = useQuery({
    queryKey: ["approval-review", targetId],
    queryFn: () => getApprovalReview(targetId),
    retry: false,
  });
  const approve = async () => {
    if (!review.data) return;
    setSubmitting(true);
    setProblem("");
    try {
      await approveTarget(targetId, review.data.gateRevision);
      await queryClient.invalidateQueries({ queryKey: ["operations"] });
      onClose();
    } catch (error) {
      setProblem(error instanceof Error ? error.message : String(error));
    } finally {
      setSubmitting(false);
    }
  };
  return (
    <div className="modal-backdrop">
      <section
        aria-labelledby="approval-dialog-title"
        aria-modal="true"
        className="confirmation-dialog action-review-dialog"
        role="dialog"
      >
        <header>
          <ShieldCheck aria-hidden="true" />
          <div>
            <span>Workflow approval</span>
            <h2 id="approval-dialog-title">
              {review.data ? `Approve ${review.data.stage}?` : "Review approval"}
            </h2>
          </div>
          <button
            aria-label="Close approval"
            className="icon-button"
            onClick={onClose}
            type="button"
          >
            <X aria-hidden="true" />
          </button>
        </header>
        {review.isPending ? (
          <div className="action-review-loading" role="status">
            <LoaderCircle className="spin" aria-hidden="true" />
            Resolving the exact approval target
          </div>
        ) : review.isError ? (
          <DialogError
            error={review.error}
            retry={() => void review.refetch()}
          />
        ) : review.data ? (
          <>
            <dl className="action-review-facts">
              <div>
                <dt>Resource</dt>
                <dd>{review.data.resourceName ?? "Workflow"}</dd>
              </div>
              <div>
                <dt>Stage</dt>
                <dd>{review.data.stage}</dd>
              </div>
              <div>
                <dt>ApprovalGate</dt>
                <dd title={review.data.gateName}>{review.data.gateName}</dd>
              </div>
            </dl>
            <div className="action-effect">
              <strong>Effect</strong>
              <p>{review.data.effect}</p>
            </div>
            {review.data.reason ? (
              <div className="action-warning">
                <AlertTriangle aria-hidden="true" />
                <span>{review.data.reason}</span>
              </div>
            ) : null}
          </>
        ) : null}
        {problem ? <p className="action-inline-error">{problem}</p> : null}
        <footer>
          <button disabled={submitting} onClick={onClose} type="button">
            Cancel
          </button>
          <button
            className="primary-button"
            disabled={submitting || !review.data}
            onClick={() => void approve()}
            type="button"
          >
            {submitting
              ? <LoaderCircle className="spin" aria-hidden="true" />
              : <ShieldCheck aria-hidden="true" />}
            Approve exact gate
          </button>
        </footer>
      </section>
    </div>
  );
}


export function ResetDialog({
  targetId,
  onClose,
}: {
  targetId: string;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const [submitting, setSubmitting] = useState(false);
  const [problem, setProblem] = useState("");
  const plan = useQuery({
    queryKey: ["reset-plan", targetId],
    queryFn: () => getResetPlan(targetId),
    retry: false,
  });
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
