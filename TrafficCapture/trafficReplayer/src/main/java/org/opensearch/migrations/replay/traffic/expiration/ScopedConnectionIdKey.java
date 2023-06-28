package org.opensearch.migrations.replay.traffic.expiration;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;

@AllArgsConstructor
@EqualsAndHashCode
class ScopedConnectionIdKey {
    public final String nodeId;
    public final String connectionId;
}
