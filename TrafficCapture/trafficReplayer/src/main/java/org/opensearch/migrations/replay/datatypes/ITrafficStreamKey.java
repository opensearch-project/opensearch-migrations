package org.opensearch.migrations.replay.datatypes;

import org.opensearch.migrations.replay.tracing.IReplayContexts;

import lombok.NonNull;

public interface ITrafficStreamKey extends ISourceTrafficChannelKey {
    int getTrafficStreamIndex();

    @NonNull
    IReplayContexts.ITrafficStreamsLifecycleContext getTrafficStreamsContext();
}
