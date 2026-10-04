package org.opensearch.migrations.replay.lifecycle;

import java.util.concurrent.CancellationException;

import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;

import lombok.NonNull;

/**
 * Defines the closed set of expected asynchronous outcomes used by replay lifecycle state machines.
 *
 * <p>Preparation cancellation, retry decisions, successful target responses, and expected no-response cases
 * are represented as values rather than exceptions. This lets lifecycle code switch exhaustively over
 * normal operational results while reserving exceptional completion for broken invariants and unexpected
 * implementation failures.</p>
 *
 * <p>The result records also validate combinations at construction—for example, a skipped transformation
 * carries no prepared request—so impossible intermediate states cannot be passed between asynchronous
 * stages and discovered only after ownership has moved.</p>
 */
public final class ReplayOutcomes {
    private ReplayOutcomes() {}

    public sealed interface RequestPreparationResult<T>
        permits RequestPreparationReady, RequestPreparationCancelled {}

    public record RequestPreparationReady<T>(
        T value,
        @NonNull HttpRequestTransformationStatus transformationStatus
    ) implements RequestPreparationResult<T> {
        public RequestPreparationReady(T value) {
            this(value, HttpRequestTransformationStatus.completed());
        }

        public RequestPreparationReady {
            if (transformationStatus.isCompleted() || transformationStatus.isError()) {
                if (value == null) {
                    throw new IllegalArgumentException(
                        "replayable request preparation requires a prepared request"
                    );
                }
            } else if (transformationStatus.isSkipped()) {
                if (value != null) {
                    throw new IllegalArgumentException(
                        "skipped request preparation must not contain a prepared request"
                    );
                }
            } else {
                throw new IllegalArgumentException(
                    "request preparation readiness must be completed, fallback-error, or skipped"
                );
            }
        }
    }

    public record RequestPreparationCancelled<T>(
        @NonNull CancellationException cause
    ) implements RequestPreparationResult<T> {}

    public sealed interface RetryDecision
        permits RetryDecision.RetryRequired,
            RetryDecision.TargetServerAttemptsFinished {

        record RetryRequired() implements RetryDecision {}

        record TargetServerAttemptsFinished() implements RetryDecision {}
    }

    public sealed interface TargetAttemptOutcome<T>
        permits TargetAttemptOutcome.TargetResponseObtained,
            TargetAttemptOutcome.NoTargetResponseObtained {

        enum NoTargetResponseKind {
            READ_TIMEOUT,
            TRANSPORT_FAILURE,
            MISSING_HTTP_RESPONSE
        }

        record NoTargetResponseDiagnostic(
            @NonNull NoTargetResponseKind kind,
            @NonNull String description
        ) {}

        <R> R visit(Visitor<T, R> visitor);

        interface Visitor<T, R> {
            R onTargetResponseObtained(TargetResponseObtained<T> outcome);

            R onNoTargetResponseObtained(NoTargetResponseObtained<T> outcome);
        }

        record TargetResponseObtained<T>(@NonNull T response) implements TargetAttemptOutcome<T> {
            @Override
            public <R> R visit(Visitor<T, R> visitor) {
                return visitor.onTargetResponseObtained(this);
            }
        }

        record NoTargetResponseObtained<T>(
            @NonNull NoTargetResponseDiagnostic diagnostic
        ) implements TargetAttemptOutcome<T> {
            public String reason() {
                return diagnostic.description();
            }

            @Override
            public <R> R visit(Visitor<T, R> visitor) {
                return visitor.onNoTargetResponseObtained(this);
            }
        }
    }

}
