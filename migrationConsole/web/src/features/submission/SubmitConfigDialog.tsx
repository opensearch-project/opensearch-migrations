import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  AlertTriangle,
  CheckCircle2,
  LoaderCircle,
  RotateCcw,
  Send,
} from "lucide-react";

import {
  ConfigApiError,
  executeReset,
  getCombinedResetPlan,
  getConfigDraft,
  getConfigPreflight,
  getConfigReview,
  submitConfigDraft,
} from "../../api/client";


interface SubmitConfigDialogProps {
  draftRevision?: string;
  intent?: "submit" | "resubmit";
  onClose: () => void;
  onSubmitted: () => void;
  reason?: string;
}


export function SubmitConfigDialog({
  draftRevision,
  intent = "submit",
  onClose,
  onSubmitted,
  reason,
}: SubmitConfigDialogProps) {
  const queryClient = useQueryClient();
  const [sessionKey] = useState(() => Math.random().toString(36).slice(2));
  const [submitting, setSubmitting] = useState(false);
  const [problem, setProblem] = useState("");
  const currentDraft = useQuery({
    queryKey: ["submission-draft", sessionKey],
    queryFn: getConfigDraft,
    enabled: draftRevision === undefined,
    staleTime: 0,
  });
  const revision = draftRevision ?? currentDraft.data?.draftRevision;
  const review = useQuery({
    queryKey: ["config-review", sessionKey, revision],
    queryFn: () => getConfigReview(revision ?? ""),
    enabled: revision !== undefined,
    retry: false,
  });
  const preflight = useQuery({
    queryKey: ["config-preflight", sessionKey, revision],
    queryFn: () => getConfigPreflight(revision ?? ""),
    enabled: revision !== undefined,
    retry: false,
  });
  const resetIssues = preflight.data?.issues.filter(
    (issue) => issue.classification === "recreate-required"
      && issue.resetTargetId,
  ) ?? [];
  const resetTargetIds = [...new Set(resetIssues.flatMap(
    (issue) => issue.resetTargetId ? [issue.resetTargetId] : [],
  ))];
  const resetPlan = useQuery({
    queryKey: ["reset-plan", "submit-preflight", ...resetTargetIds],
    queryFn: () => getCombinedResetPlan(resetTargetIds),
    enabled: resetTargetIds.length > 0,
    retry: false,
  });
  const hasNonResetBlocker = preflight.data?.issues.some(
    (issue) => issue.blocking && !issue.resetTargetId,
  ) ?? false;
  const loading = (
    draftRevision === undefined && currentDraft.isPending
  ) || (
    revision !== undefined
    && (review.isPending || preflight.isPending)
  );
  const loadError = currentDraft.error ?? review.error ?? preflight.error;
  const resubmitting = intent === "resubmit";

  const retry = () => {
    setProblem("");
    if (currentDraft.isError) void currentDraft.refetch();
    else {
      void review.refetch();
      void preflight.refetch();
    }
  };

  const submit = async () => {
    if (!review.data) return;
    setSubmitting(true);
    setProblem("");
    try {
      await submitConfigDraft(review.data.draftRevision);
      queryClient.removeQueries({ queryKey: ["config-draft"] });
      queryClient.removeQueries({ queryKey: ["submission-draft"] });
      void queryClient.invalidateQueries({ queryKey: ["operations"] });
      void queryClient.invalidateQueries({ queryKey: ["manage-state"] });
      onSubmitted();
    } catch (error) {
      if (error instanceof ConfigApiError && error.current) {
        queryClient.setQueryData(["config-draft"], error.current);
      }
      setProblem(error instanceof Error ? error.message : String(error));
    } finally {
      setSubmitting(false);
    }
  };
  const resetAndResubmit = async () => {
    if (!review.data || !resetPlan.data) return;
    setSubmitting(true);
    setProblem("");
    try {
      await executeReset(resetPlan.data.token, {
        resubmit: true,
        expectedDraftRevision: review.data.draftRevision,
      });
      queryClient.removeQueries({ queryKey: ["config-draft"] });
      queryClient.removeQueries({ queryKey: ["submission-draft"] });
      void queryClient.invalidateQueries({ queryKey: ["operations"] });
      void queryClient.invalidateQueries({ queryKey: ["manage-state"] });
      onSubmitted();
    } catch (error) {
      if (error instanceof ConfigApiError && error.current) {
        queryClient.setQueryData(["config-draft"], error.current);
      }
      setProblem(error instanceof Error ? error.message : String(error));
      void resetPlan.refetch();
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="modal-backdrop">
      <section
        aria-labelledby="submit-dialog-title"
        aria-modal="true"
        className="confirmation-dialog"
        role="dialog"
      >
        <header>
          <Send aria-hidden="true" />
          <div>
            <span>Workflow submission</span>
            <h2 id="submit-dialog-title">
              {resubmitting ? "Resubmit configuration?" : "Submit configuration?"}
            </h2>
          </div>
        </header>
        {loading ? (
          <div className="submit-review-state" role="status">
            <LoaderCircle className="spin" aria-hidden="true" />
            Preparing change review
          </div>
        ) : loadError ? (
          <div className="submit-review-invalid" role="alert">
            <AlertTriangle aria-hidden="true" />
            <span>{loadError.message}</span>
            <button onClick={retry} type="button">Retry</button>
          </div>
        ) : review.data ? (
          <div className="submit-review">
            {resubmitting ? (
              <>
                <p>
                  The saved configuration will be submitted again to recreate
                  missing resources or retry failed workflow work.
                </p>
                {reason ? (
                  <p className="submit-review-empty">{reason}</p>
                ) : null}
              </>
            ) : (
              <p>
                The current pending configuration will be saved and workflow
                replacement will continue as a tracked operation.
              </p>
            )}
            {review.data.changes.length > 0 ? (
              <ul className="submit-change-list">
                {review.data.changes.map((change) => (
                  <li key={`${change.resourceId ?? "config"}-${change.path}`}>
                    <strong>
                      {change.resourceLabel ?? change.label}
                    </strong>
                    <span>{change.resourceLabel
                      ? change.label
                      : change.path}</span>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="submit-review-empty">
                {resubmitting
                  ? "No configuration differences were reported; resubmission will retry the saved configuration."
                  : "No field-level pending differences were reported."}
              </p>
            )}
            {!review.data.valid ? (
              <div className="submit-review-invalid" role="alert">
                <AlertTriangle aria-hidden="true" />
                <span>
                  {review.data.validationMessages.join(" ")
                    || "Resolve validation errors before submitting."}
                </span>
              </div>
            ) : null}
            {preflight.data ? (
              <section
                aria-label="Admission preflight"
                className="submit-preflight"
              >
                <header>
                  {preflight.data.issues.length === 0
                    ? <CheckCircle2 aria-hidden="true" />
                    : <AlertTriangle aria-hidden="true" />}
                  <div>
                    <strong>Cluster admission preflight</strong>
                    <span>
                      {preflight.data.checkedResources} {
                        preflight.data.checkedResources === 1
                          ? "resource checked"
                          : "resources checked"
                      }
                    </span>
                  </div>
                </header>
                {preflight.data.issues.length === 0 ? (
                  <p>No admission conflicts were found.</p>
                ) : (
                  <ul>
                    {preflight.data.issues.map((issue) => (
                      <li key={`${issue.kind}-${issue.name}-${issue.message}`}>
                        <div>
                          <strong>{issue.name}</strong>
                          <span>{issue.kind}</span>
                        </div>
                        <p>{issue.message}</p>
                        <small>
                          {issue.classification === "recreate-required"
                            ? "Reset required"
                            : issue.classification === "invalid"
                              ? "Submission blocked"
                              : issue.classification === "approval-required"
                                ? "The workflow can request approval"
                                : "Preflight warning"}
                        </small>
                      </li>
                    ))}
                  </ul>
                )}
                {resetTargetIds.length > 0 ? (
                  <div className="submit-reset-plan">
                    {resetPlan.isPending ? (
                      <span>
                        <LoaderCircle className="spin" aria-hidden="true" />
                        Building dependency-safe reset plan
                      </span>
                    ) : resetPlan.isError ? (
                      <span className="submit-reset-error">
                        {resetPlan.error.message}
                      </span>
                    ) : resetPlan.data ? (
                      <>
                        <strong>
                          Reset plan: {resetPlan.data.targets.length} {
                            resetPlan.data.targets.length === 1
                              ? "resource"
                              : "resources"
                          }
                        </strong>
                        <ol>
                          {resetPlan.data.targets.map((target) => (
                            <li key={`${target.plural}-${target.name}`}>
                              {target.path}
                            </li>
                          ))}
                        </ol>
                      </>
                    ) : null}
                  </div>
                ) : null}
              </section>
            ) : null}
          </div>
        ) : null}
        {problem ? (
          <div className="submit-review-invalid" role="alert">
            <AlertTriangle aria-hidden="true" />
            <span>{problem}</span>
          </div>
        ) : null}
        <footer>
          <button disabled={submitting} onClick={onClose} type="button">
            Cancel
          </button>
          {resetTargetIds.length > 0 && !hasNonResetBlocker ? (
            <button
              className="danger-confirm"
              disabled={submitting || !resetPlan.data}
              onClick={() => void resetAndResubmit()}
              type="button"
            >
              {submitting
                ? <LoaderCircle className="spin" aria-hidden="true" />
                : <RotateCcw aria-hidden="true" />}
              Reset &amp; resubmit ({resetTargetIds.length})
            </button>
          ) : null}
          <button
            aria-label={resubmitting ? "Confirm resubmit" : "Confirm submit"}
            className="primary-button"
            disabled={
              submitting
              || loading
              || !review.data
              || !review.data.valid
              || !preflight.data
              || !preflight.data.allowed
            }
            onClick={() => void submit()}
            type="button"
          >
            {submitting
              ? <LoaderCircle className="spin" aria-hidden="true" />
              : <Send aria-hidden="true" />}
            {resubmitting
              ? "Resubmit configuration"
              : "Submit configuration"}
          </button>
        </footer>
      </section>
    </div>
  );
}
