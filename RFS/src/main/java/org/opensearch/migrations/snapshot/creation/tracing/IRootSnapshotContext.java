package org.opensearch.migrations.snapshot.creation.tracing;

import org.opensearch.migrations.bulkload.tracing.IRfsContexts;

public interface IRootSnapshotContext {
    IRfsContexts.ICreateSnapshotContext createSnapshotCreateContext();
}
