package org.opensearch.migrations.replay.datatypes;

import lombok.NonNull;
import org.opensearch.migrations.replay.tracing.IContexts;

public interface ITrafficStreamKey extends ISourceTrafficChannelKey {
    int getTrafficStreamIndex();
}
