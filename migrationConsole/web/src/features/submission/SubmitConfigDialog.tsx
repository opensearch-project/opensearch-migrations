import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, LoaderCircle, Send } from "lucide-react";

import {
  ConfigApiError,
  getConfigDraft,
  getConfigReview,
  submitConfigDraft,
} from "../../api/client";


interface SubmitConfigDialogProps {
  draftRevision?: string;
  onClose: () => void;
  onSubmitted: () => void;
}


export function SubmitConfigDialog({
  draftRevision,
  onClose,
  onSubmitted,
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
  const loading = (
    draftRevision === undefined && currentDraft.isPending
  ) || (revision !== undefined && review.isPending);
  const loadError = currentDraft.error ?? review.error;

  const retry = () => {
    setProblem("");
    if (currentDraft.isError) void currentDraft.refetch();
    else void review.refetch();
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
            <h2 id="submit-dialog-title">Submit configuration?</h2>
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
            <p>
              The current pending configuration will be saved and workflow
              replacement will continue as a tracked operation.
            </p>
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
                No field-level pending differences were reported.
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
          <button
            aria-label="Confirm submit"
            className="primary-button"
            disabled={
              submitting
              || loading
              || !review.data
              || !review.data.valid
            }
            onClick={() => void submit()}
            type="button"
          >
            {submitting
              ? <LoaderCircle className="spin" aria-hidden="true" />
              : <Send aria-hidden="true" />}
            Submit configuration
          </button>
        </footer>
      </section>
    </div>
  );
}
