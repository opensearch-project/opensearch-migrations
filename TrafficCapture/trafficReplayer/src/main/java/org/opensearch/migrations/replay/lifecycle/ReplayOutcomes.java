package org.opensearch.migrations.replay.lifecycle;

import java.util.concurrent.CancellationException;

import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;

import lombok.NonNull;

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
