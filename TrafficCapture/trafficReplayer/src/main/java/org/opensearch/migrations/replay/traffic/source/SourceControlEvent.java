package org.opensearch.migrations.replay.traffic.source;

import lombok.NonNull;

public sealed interface SourceControlEvent extends SourceInput permits SourceControlEvent.ConfirmedDead {
    record ConfirmedDead(@NonNull ScanEvidence.ConfirmedAbsent evidence) implements SourceControlEvent {}
}
