package org.opensearch.migrations.replay.lifecycle;

import lombok.NonNull;

public sealed interface RecordDisposition permits RecordDisposition.Commit, RecordDisposition.Retain {
    String reasonCode();

    record Commit(@NonNull String reasonCode) implements RecordDisposition {}

    record Retain(@NonNull String reasonCode) implements RecordDisposition {}
}
