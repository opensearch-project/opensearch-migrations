package org.opensearch.migrations.replay.datatypes;

import lombok.AllArgsConstructor;

@AllArgsConstructor
public class UniqueRequestKey {
    public final String connectionId;
    public final int requestIndex;

    @Override
    public String toString() {
        return connectionId + '.' + requestIndex;
    }
}
