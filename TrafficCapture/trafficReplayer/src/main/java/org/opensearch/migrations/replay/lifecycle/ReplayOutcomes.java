package org.opensearch.migrations.replay.lifecycle;

import java.util.concurrent.CancellationException;

import lombok.NonNull;

public final class ReplayOutcomes {
    private ReplayOutcomes() {}

    public sealed interface PreparationOutcome<T>
        permits PreparationOutcome.Prepared,
            PreparationOutcome.Filtered,
            PreparationOutcome.Failed,
            PreparationOutcome.Cancelled {

        <R> R visit(Visitor<T, R> visitor);

        interface Visitor<T, R> {
            R onPrepared(Prepared<T> outcome);

            R onFiltered(Filtered<T> outcome);

            R onFailed(Failed<T> outcome);

            R onCancelled(Cancelled<T> outcome);
        }

        record Prepared<T>(@NonNull T value) implements PreparationOutcome<T> {
            @Override
            public <R> R visit(Visitor<T, R> visitor) {
                return visitor.onPrepared(this);
            }
        }

        record Filtered<T>(@NonNull String reason) implements PreparationOutcome<T> {
            @Override
            public <R> R visit(Visitor<T, R> visitor) {
                return visitor.onFiltered(this);
            }
        }

        record Failed<T>(@NonNull Throwable cause) implements PreparationOutcome<T> {
            @Override
            public <R> R visit(Visitor<T, R> visitor) {
                return visitor.onFailed(this);
            }
        }

        record Cancelled<T>(@NonNull CancellationException cause) implements PreparationOutcome<T> {
            @Override
            public <R> R visit(Visitor<T, R> visitor) {
                return visitor.onCancelled(this);
            }
        }
    }

    public sealed interface TargetOutcome<T>
        permits TargetOutcome.Succeeded,
            TargetOutcome.Failed,
            TargetOutcome.Cancelled,
            TargetOutcome.Filtered,
            TargetOutcome.ClassifiedSkip {

        <R> R visit(Visitor<T, R> visitor);

        interface Visitor<T, R> {
            R onSucceeded(Succeeded<T> outcome);

            R onFailed(Failed<T> outcome);

            R onCancelled(Cancelled<T> outcome);

            R onFiltered(Filtered<T> outcome);

            R onClassifiedSkip(ClassifiedSkip<T> outcome);
        }

        record Succeeded<T>(T value) implements TargetOutcome<T> {
            @Override
            public <R> R visit(Visitor<T, R> visitor) {
                return visitor.onSucceeded(this);
            }
        }

        record Failed<T>(@NonNull Throwable cause) implements TargetOutcome<T> {
            @Override
            public <R> R visit(Visitor<T, R> visitor) {
                return visitor.onFailed(this);
            }
        }

        record Cancelled<T>(@NonNull CancellationException cause) implements TargetOutcome<T> {
            @Override
            public <R> R visit(Visitor<T, R> visitor) {
                return visitor.onCancelled(this);
            }
        }

        record Filtered<T>(@NonNull String reason) implements TargetOutcome<T> {
            @Override
            public <R> R visit(Visitor<T, R> visitor) {
                return visitor.onFiltered(this);
            }
        }

        record ClassifiedSkip<T>(T value, @NonNull String reason) implements TargetOutcome<T> {
            @Override
            public <R> R visit(Visitor<T, R> visitor) {
                return visitor.onClassifiedSkip(this);
            }
        }
    }

    public sealed interface SourceOutcome
        permits SourceOutcome.Complete,
            SourceOutcome.ConfirmedDead,
            SourceOutcome.CapturedClose,
            SourceOutcome.Inconclusive,
            SourceOutcome.Interrupted,
            SourceOutcome.Shutdown {

        <R> R visit(Visitor<R> visitor);

        interface Visitor<R> {
            R onComplete(Complete outcome);

            R onConfirmedDead(ConfirmedDead outcome);

            R onCapturedClose(CapturedClose outcome);

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
}
