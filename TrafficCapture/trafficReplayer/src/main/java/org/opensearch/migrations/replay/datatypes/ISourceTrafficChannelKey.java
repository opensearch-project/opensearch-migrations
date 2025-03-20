package org.opensearch.migrations.replay.datatypes;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;

public interface ISourceTrafficChannelKey {
    String getNodeId();

    String getConnectionId();

    @Getter
    @AllArgsConstructor
    @EqualsAndHashCode
    class PojoImpl implements ISourceTrafficChannelKey {
        String nodeId;
        String connectionId;
    }
}
