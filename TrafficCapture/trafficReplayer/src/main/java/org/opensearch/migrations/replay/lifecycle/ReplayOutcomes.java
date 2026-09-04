package org.opensearch.migrations.replay.lifecycle;

import java.util.concurrent.CancellationException;

import lombok.NonNull;

public final class ReplayOutcomes {
    private ReplayOutcomes() {}

    public sealed interface TargetOutcome<T>
        permits TargetOutcome.Succeeded, TargetOutcome.Failed, TargetOutcome.Cancelled, TargetOutcome.Filtered {

        <R> R visit(Visitor<T, R> visitor);

        interface Visitor<T, R> {
            R onSucceeded(Succeeded<T> outcome);

            R onFailed(Failed<T> outcome);

            R onCancelled(Cancelled<T> outcome);

            R onFiltered(Filtered<T> outcome);
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
    }

    public sealed interface SourceOutcome
        permits SourceOutcome.Complete,
            SourceOutcome.ConfirmedDead,
            SourceOutcome.CapturedClose,
            SourceOutcome.Interrupted,
            SourceOutcome.Shutdown {

        <R> R visit(Visitor<R> visitor);

        interface Visitor<R> {
            R onComplete(Complete outcome);

            R onConfirmedDead(ConfirmedDead outcome);

            R onCapturedClose(CapturedClose outcome);

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
}
