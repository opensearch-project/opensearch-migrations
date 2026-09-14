import { useEffect, useId, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  AlertTriangle,
  CheckCircle2,
  LoaderCircle,
  RefreshCw,
  Send,
  Trash2,
} from "lucide-react";

import {
  ConfigApiError,
  executeReset,
  getCombinedResetPlan,
  getConfigurationDocument,
  getConfigPreflight,
  getConfigReview,
  submitSavedConfiguration,
} from "../../api/client";
import { ModalDialog } from "../../components/ModalDialog";


interface SubmitConfigDialogProps {
  persistedRevision?: string;
  intent?: "submit" | "resubmit";
  onClose: () => void;
  onSubmitted: () => void;
  reason?: string;
}


export function SubmitConfigDialog({
  persistedRevision,
  intent = "submit",
  onClose,
  onSubmitted,
  reason,
}: Readonly<SubmitConfigDialogProps>) {
  const queryClient = useQueryClient();
  const sessionKey = useId();
  const [submitting, setSubmitting] = useState(false);
  const [problem, setProblem] = useState("");
  const currentDocument = useQuery({
    queryKey: ["submission-document", sessionKey],
    queryFn: getConfigurationDocument,
    enabled: persistedRevision === undefined,
    staleTime: 0,
  });
  const revision = (
    persistedRevision ?? currentDocument.data?.persistedRevision
  );
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
  const deploymentActions = preflight.data?.deploymentActions ?? [];
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
    persistedRevision === undefined && currentDocument.isPending
  ) || (
    revision !== undefined
    && (review.isPending || preflight.isPending)
  );
  const loadError = currentDocument.error ?? review.error ?? preflight.error;
  const resubmitting = intent === "resubmit";
  const resetActionCount = resetPlan.data?.targets.length
    ?? resetTargetIds.length;
  const directSubmitTitle = resetTargetIds.length > 0
    && preflight.data?.allowed === false
    ? (
      "No workflow will be submitted while admission errors requiring "
      + "resource deletion remain. The affected resources and their "
      + "dependencies will stay "
      + "blocked. Use Delete resources and resubmit."
    )
    : undefined;
  const resetAndResubmitTitle = resetPlan.data
    ? `Delete ${resetPlan.data.targets.length} ${
      resetPlan.data.targets.length === 1 ? "resource" : "resources"
    } before submitting a new workflow: ${
      resetPlan.data.targets.map((target) => target.path).join("; ")
    }.`
    : "Building the dependency-safe resource deletion plan.";
  useEffect(() => () => {
    // Session-keyed queries are unreachable after the dialog closes.
    queryClient.removeQueries({
      queryKey: ["submission-document", sessionKey],
    });
    queryClient.removeQueries({ queryKey: ["config-review", sessionKey] });
    queryClient.removeQueries({ queryKey: ["config-preflight", sessionKey] });
  }, [queryClient, sessionKey]);

  const retry = () => {
    setProblem("");
    if (currentDocument.isError) void currentDocument.refetch();
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
      await submitSavedConfiguration(review.data.persistedRevision);
      queryClient.removeQueries({ queryKey: ["submission-document"] });
      void queryClient.invalidateQueries({ queryKey: ["operations"] });
      void queryClient.invalidateQueries({ queryKey: ["manage-state"] });
      onSubmitted();
    } catch (error) {
      if (error instanceof ConfigApiError && error.currentDocument) {
        queryClient.setQueryData(
          ["submission-document", sessionKey],
          error.currentDocument,
        );
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
        expectedPersistedRevision: review.data.persistedRevision,
      });
      queryClient.removeQueries({ queryKey: ["submission-document"] });
      void queryClient.invalidateQueries({ queryKey: ["operations"] });
      void queryClient.invalidateQueries({ queryKey: ["manage-state"] });
      onSubmitted();
    } catch (error) {
      if (error instanceof ConfigApiError && error.currentDocument) {
        queryClient.setQueryData(
          ["submission-document", sessionKey],
          error.currentDocument,
        );
      }
      setProblem(error instanceof Error ? error.message : String(error));
      void resetPlan.refetch();
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <ModalDialog
      className="submission-dialog"
      closeLabel="Close submission review"
      escapeDisabled={submitting}
      icon={<Send aria-hidden="true" />}
      kicker="Workflow submission"
      onClose={onClose}
      title={resubmitting ? "Resubmit configuration?" : "Submit configuration?"}
      footer={(
        <>
          <button disabled={submitting} onClick={onClose} type="button">
            Cancel
          </button>
          {resetTargetIds.length > 0 && !hasNonResetBlocker ? (
            <button
              className="danger-confirm"
              disabled={submitting || !resetPlan.data}
              onClick={() => void resetAndResubmit()}
              title={resetAndResubmitTitle}
              type="button"
            >
              {submitting
                ? <LoaderCircle className="spin" aria-hidden="true" />
                : <Trash2 aria-hidden="true" />}
              Delete resources and resubmit ({resetActionCount})
            </button>
          ) : null}
          <button
            aria-label={resubmitting ? "Confirm resubmit" : "Confirm submit"}
            className="primary-button"
            disabled={
              submitting
              || loading
              || !review.data?.valid
              || !preflight.data?.allowed
            }
            onClick={() => void submit()}
            title={directSubmitTitle}
            type="button"
          >
            {submitting
              ? <LoaderCircle className="spin" aria-hidden="true" />
              : <Send aria-hidden="true" />}
            {resubmitting
              ? "Resubmit configuration"
              : "Submit configuration"}
          </button>
        </>
      )}
    >
        <div className="submission-dialog-body">
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
              ) : null}
              {!review.data.valid ? (
                <div className="submit-review-invalid" role="alert">
                  <AlertTriangle aria-hidden="true" />
                  <span>
                    {review.data.validationMessages.join(" ")
                      || "Resolve validation errors before submitting."}
                  </span>
                </div>
              ) : null}
              {deploymentActions.length > 0 ? (
                <section
                  aria-label="Deployment impact"
                  className="submit-preflight deployment-impact"
                >
                  <header>
                    <RefreshCw aria-hidden="true" />
                    <div>
                      <strong>Deployment impact</strong>
                      <span>
                        {deploymentActions.length} {
                          deploymentActions.length === 1
                            ? "resource will change"
                            : "resources will change"
                        }
                      </span>
                    </div>
                  </header>
                  <ul>
                    {deploymentActions.map((action) => (
                      <li key={`${action.kind}-${action.name}-${action.reason}`}>
                        <div>
                          <strong>{action.name}</strong>
                          <span>{action.kind}</span>
                        </div>
                        <p>{action.message}</p>
                        <small>
                          {action.reason === "checksum-only"
                            ? "Checksum-only reconcile"
                            : action.reason === "resource-missing"
                              ? "Create missing resource"
                              : action.reason === "resource-not-ready"
                                ? "Retry incomplete resource"
                                : "Configuration reconcile"}
                        </small>
                      </li>
                    ))}
                  </ul>
                </section>
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
                              ? "Resource deletion required"
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
                          Building dependency-safe resource deletion plan
                        </span>
                      ) : resetPlan.isError ? (
                        <span className="submit-reset-error">
                          {resetPlan.error.message}
                        </span>
                      ) : resetPlan.data ? (
                        <>
                          <strong>
                            Resource deletion plan: {
                              resetPlan.data.targets.length
                            } {
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
        </div>
    </ModalDialog>
  );
}
