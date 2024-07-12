package org.opensearch.migrations.snapshot.creation.tracing;

import com.rfs.tracing.IRfsContexts;

public interface IRootSnapshotContext {
    IRfsContexts.ICreateSnapshotContext createSnapshotCreateContext();
}
