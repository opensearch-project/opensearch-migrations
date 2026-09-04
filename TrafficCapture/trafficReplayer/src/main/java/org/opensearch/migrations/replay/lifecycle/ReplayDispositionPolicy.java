package org.opensearch.migrations.replay.lifecycle;

import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.EvidenceOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SourceOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetOutcome;

import lombok.NonNull;

public final class ReplayDispositionPolicy {
    public record Decision(@NonNull RecordDisposition disposition, boolean haltReplay) {}

    public <T> Decision decide(
        @NonNull SourceOutcome source,
        @NonNull TargetOutcome<T> target,
        @NonNull EvidenceOutcome evidence
    ) {
        return evidence.visit(new EvidenceOutcome.Visitor<>() {
            @Override
            public Decision onDurable(EvidenceOutcome.Durable outcome) {
                return decideWithDurableEvidence(source, target);
            }

            @Override
            public Decision onFailed(EvidenceOutcome.Failed outcome) {
                return retain("evidence-failed", true);
            }

            @Override
            public Decision onNotRequired(EvidenceOutcome.NotRequired outcome) {
                return decideWithoutEvidence(source, target);
            }
        });
    }

    public <T> boolean requiresEvidence(@NonNull SourceOutcome source, @NonNull TargetOutcome<T> target) {
        return source.visit(new SourceOutcome.Visitor<>() {
            @Override
            public Boolean onComplete(SourceOutcome.Complete outcome) {
                return targetRequiresEvidence(target);
            }

            @Override
            public Boolean onConfirmedDead(SourceOutcome.ConfirmedDead outcome) {
                return targetRequiresEvidence(target);
            }

            @Override
            public Boolean onCapturedClose(SourceOutcome.CapturedClose outcome) {
                return targetRequiresEvidence(target);
            }

            @Override
            public Boolean onInconclusive(SourceOutcome.Inconclusive outcome) {
                return targetRequiresEvidence(target);
            }

            @Override
            public Boolean onInterrupted(SourceOutcome.Interrupted outcome) {
                return false;
            }

            @Override
            public Boolean onShutdown(SourceOutcome.Shutdown outcome) {
                return false;
            }
        });
    }

    private <T> Decision decideWithDurableEvidence(SourceOutcome source, TargetOutcome<T> target) {
        return source.visit(new SourceOutcome.Visitor<>() {
            @Override
            public Decision onComplete(SourceOutcome.Complete outcome) {
                return decideDurableTarget(target);
            }

            @Override
            public Decision onConfirmedDead(SourceOutcome.ConfirmedDead outcome) {
                return decideDurableTarget(target);
            }

            @Override
            public Decision onCapturedClose(SourceOutcome.CapturedClose outcome) {
                return decideDurableTarget(target);
            }

            @Override
            public Decision onInconclusive(SourceOutcome.Inconclusive outcome) {
                return retain("source-inconclusive", true);
            }

            @Override
            public Decision onInterrupted(SourceOutcome.Interrupted outcome) {
                return retain("source-interrupted", false);
            }

            @Override
            public Decision onShutdown(SourceOutcome.Shutdown outcome) {
                return retain("shutdown", false);
            }
        });
    }

    private <T> Decision decideWithoutEvidence(SourceOutcome source, TargetOutcome<T> target) {
        return source.visit(new SourceOutcome.Visitor<>() {
            @Override
            public Decision onComplete(SourceOutcome.Complete outcome) {
                return retainForTargetWithoutEvidence(target);
            }

            @Override
            public Decision onConfirmedDead(SourceOutcome.ConfirmedDead outcome) {
                return retainForTargetWithoutEvidence(target);
            }

            @Override
            public Decision onCapturedClose(SourceOutcome.CapturedClose outcome) {
                return retainForTargetWithoutEvidence(target);
            }

            @Override
            public Decision onInconclusive(SourceOutcome.Inconclusive outcome) {
                return retain("source-inconclusive", true);
            }

            @Override
            public Decision onInterrupted(SourceOutcome.Interrupted outcome) {
                return retain("source-interrupted", false);
            }

            @Override
            public Decision onShutdown(SourceOutcome.Shutdown outcome) {
                return retain("shutdown", false);
            }
        });
    }

    private <T> Decision decideDurableTarget(TargetOutcome<T> target) {
        return target.visit(new TargetOutcome.Visitor<T, Decision>() {
            @Override
            public Decision onSucceeded(TargetOutcome.Succeeded<T> outcome) {
                return commit("replay-succeeded");
            }

            @Override
            public Decision onFailed(TargetOutcome.Failed<T> outcome) {
                return retain("target-failed", true);
            }

            @Override
            public Decision onCancelled(TargetOutcome.Cancelled<T> outcome) {
                return retain("target-cancelled", false);
            }

            @Override
            public Decision onFiltered(TargetOutcome.Filtered<T> outcome) {
                return commit("request-filtered");
            }

            @Override
            public Decision onClassifiedSkip(TargetOutcome.ClassifiedSkip<T> outcome) {
                return commit("target-classified-skip");
            }
        });
    }

    private <T> Decision retainForTargetWithoutEvidence(TargetOutcome<T> target) {
        return target.visit(new TargetOutcome.Visitor<T, Decision>() {
            @Override
            public Decision onSucceeded(TargetOutcome.Succeeded<T> outcome) {
                return retain("durable-evidence-missing", true);
            }

            @Override
            public Decision onFailed(TargetOutcome.Failed<T> outcome) {
                return retain("target-failed", true);
            }

            @Override
            public Decision onCancelled(TargetOutcome.Cancelled<T> outcome) {
                return retain("target-cancelled", false);
            }

            @Override
            public Decision onFiltered(TargetOutcome.Filtered<T> outcome) {
                return retain("durable-evidence-missing", true);
            }

            @Override
            public Decision onClassifiedSkip(TargetOutcome.ClassifiedSkip<T> outcome) {
                return retain("durable-evidence-missing", true);
            }
        });
    }

    private <T> boolean targetRequiresEvidence(TargetOutcome<T> target) {
        return target.visit(new TargetOutcome.Visitor<T, Boolean>() {
            @Override
            public Boolean onSucceeded(TargetOutcome.Succeeded<T> outcome) {
                return true;
            }

            @Override
            public Boolean onFailed(TargetOutcome.Failed<T> outcome) {
                return true;
            }

            @Override
            public Boolean onCancelled(TargetOutcome.Cancelled<T> outcome) {
                return false;
            }

            @Override
            public Boolean onFiltered(TargetOutcome.Filtered<T> outcome) {
                return true;
            }

            @Override
            public Boolean onClassifiedSkip(TargetOutcome.ClassifiedSkip<T> outcome) {
                return true;
            }
        });
    }

    private static Decision commit(String reason) {
        return new Decision(new RecordDisposition.Commit(reason), false);
    }

    private static Decision retain(String reason, boolean haltReplay) {
        return new Decision(new RecordDisposition.Retain(reason), haltReplay);
    }
}
