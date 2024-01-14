package org.opensearch.migrations.replay.datatypes;

import lombok.NonNull;
import org.opensearch.migrations.replay.tracing.IReplayContexts;

public interface ITrafficStreamKey extends ISourceTrafficChannelKey {
    int getTrafficStreamIndex();
    @NonNull IReplayContexts.ITrafficStreamsLifecycleContext getTrafficStreamsContext();
}
