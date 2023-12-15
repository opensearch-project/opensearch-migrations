package org.opensearch.migrations.replay.tracing;

import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;
import org.opensearch.migrations.tracing.commoncontexts.IConnectionContext;

public interface IChannelKeyContext extends IConnectionContext {
    // do not add this as a property
    // because its components are already being added in the IConnectionContext implementation
    ISourceTrafficChannelKey getChannelKey();

    default String getConnectionId() {
        return getChannelKey().getConnectionId();
    }

    default String getNodeId() {
        return getChannelKey().getNodeId();
    }
}
