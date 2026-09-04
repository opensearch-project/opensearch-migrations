package org.opensearch.migrations.replay.datatypes;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;

public interface ISourceTrafficChannelKey {
    String getNodeId();

    String getConnectionId();

    default int getSourceGeneration() {
        return 0;
    }

    @Getter
    @AllArgsConstructor
    @EqualsAndHashCode
    class PojoImpl implements ISourceTrafficChannelKey {
        String nodeId;
        String connectionId;
    }
}
