package org.opensearch.migrations.replay.lifecycle;

import java.util.concurrent.CancellationException;

import lombok.NonNull;

public final class ReplayOutcomes {
    private ReplayOutcomes() {}

    public sealed interface RequestPreparationResult<T>
        permits RequestPreparationResult.RequestPreparationReady,
            RequestPreparationResult.RequestPreparationCancelled {

        record RequestPreparationReady<T>(@NonNull T value) implements RequestPreparationResult<T> {}

        record RequestPreparationCancelled<T>(
            @NonNull CancellationException cause
        ) implements RequestPreparationResult<T> {}
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

// REBUILD-LIMBO-START(G5)
// Four outcome families no design names. Every consumer is already inside a G5 or G11 region, so none has a
// live caller; what kept them compiling was one test asserting that a visitor covers the type it was written
// against. G5 is the region's milestone because it is the milestone that opens this file anyway, for the
// PreparationOutcome strip connLLD §6 requires -- it resolves these four then, deleting or re-marking the
// pair whose consumers belong to G11.
/*
    public sealed interface ProcessingCancellationResult
        permits ProcessingCancellationResult.CancellationWon,
            ProcessingCancellationResult.ProcessingCompletionWon {

        record CancellationWon() implements ProcessingCancellationResult {}

        record ProcessingCompletionWon() implements ProcessingCancellationResult {}
    }

    public sealed interface SourceOutcome
        permits SourceOutcome.Complete,
            SourceOutcome.ConfirmedDead,
            SourceOutcome.CapturedClose,
            SourceOutcome.LegacyExpired,
            SourceOutcome.Inconclusive,
            SourceOutcome.Interrupted,
            SourceOutcome.Shutdown {

        <R> R visit(Visitor<R> visitor);

        interface Visitor<R> {
            R onComplete(Complete outcome);

            R onConfirmedDead(ConfirmedDead outcome);

            R onCapturedClose(CapturedClose outcome);

            R onLegacyExpired(LegacyExpired outcome);

            R onInconclusive(Inconclusive outcome);

            R onInterrupted(Interrupted outcome);

            R onShutdown(Shutdown outcome);
        }

        record Complete() implements SourceOutcome {
            @Override
            public <R> R visit(Visitor<R> visitor) {
                return visitor.onComplete(this);
            }
        }

        record ConfirmedDead(@NonNull String proofId) implements SourceOutcome {
            @Override
            public <R> R visit(Visitor<R> visitor) {
                return visitor.onConfirmedDead(this);
            }
        }

        record CapturedClose() implements SourceOutcome {
            @Override
            public <R> R visit(Visitor<R> visitor) {
                return visitor.onCapturedClose(this);
            }
        }

        record LegacyExpired() implements SourceOutcome {
            @Override
            public <R> R visit(Visitor<R> visitor) {
                return visitor.onLegacyExpired(this);
            }
        }

        record Inconclusive(@NonNull String reason) implements SourceOutcome {
            @Override
            public <R> R visit(Visitor<R> visitor) {
                return visitor.onInconclusive(this);
            }
        }

        record Interrupted(@NonNull String reason) implements SourceOutcome {
            @Override
            public <R> R visit(Visitor<R> visitor) {
                return visitor.onInterrupted(this);
            }
        }

        record Shutdown(@NonNull String reason) implements SourceOutcome {
            @Override
            public <R> R visit(Visitor<R> visitor) {
                return visitor.onShutdown(this);
            }
        }
    }

    public sealed interface EvidenceOutcome
        permits EvidenceOutcome.Durable, EvidenceOutcome.Failed, EvidenceOutcome.NotRequired {

        <R> R visit(Visitor<R> visitor);

        interface Visitor<R> {
            R onDurable(Durable outcome);

            R onFailed(Failed outcome);

            R onNotRequired(NotRequired outcome);
        }

        record Durable(@NonNull String receipt) implements EvidenceOutcome {
            @Override
            public <R> R visit(Visitor<R> visitor) {
                return visitor.onDurable(this);
            }
        }

        record Failed(@NonNull Throwable cause) implements EvidenceOutcome {
            @Override
            public <R> R visit(Visitor<R> visitor) {
                return visitor.onFailed(this);
            }
        }

        record NotRequired(@NonNull String reason) implements EvidenceOutcome {
            @Override
            public <R> R visit(Visitor<R> visitor) {
                return visitor.onNotRequired(this);
            }
        }
    }

    public sealed interface SessionOutcome
        permits SessionOutcome.Closed, SessionOutcome.Aborted, SessionOutcome.Failed {
        enum AbortReason {
            SOURCE_REASSIGNMENT,
            SHUTDOWN,
            DEPENDENCY_CANCELLED,
            SESSION_TERMINATED
        }

        <R> R visit(Visitor<R> visitor);

        interface Visitor<R> {
            R onClosed(Closed outcome);

            R onAborted(Aborted outcome);

            R onFailed(Failed outcome);
        }

        record Closed() implements SessionOutcome {
            @Override
            public <R> R visit(Visitor<R> visitor) {
                return visitor.onClosed(this);
            }
        }

        record Aborted(
            @NonNull AbortReason reason,
            @NonNull CancellationException cause
        ) implements SessionOutcome {
            @Override
            public <R> R visit(Visitor<R> visitor) {
                return visitor.onAborted(this);
            }
        }

        record Failed(@NonNull Throwable cause) implements SessionOutcome {
            @Override
            public <R> R visit(Visitor<R> visitor) {
                return visitor.onFailed(this);
            }
        }
    }

*/
// REBUILD-LIMBO-END(G5)
}
