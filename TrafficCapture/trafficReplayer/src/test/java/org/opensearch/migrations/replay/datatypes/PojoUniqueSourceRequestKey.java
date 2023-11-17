package org.opensearch.migrations.replay.datatypes;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
public class PojoUniqueSourceRequestKey extends UniqueSourceRequestKey {
    @Getter
    public final ISourceTrafficChannelKey trafficStreamKey;
    @Getter
    public final int sourceRequestIndex;
}
