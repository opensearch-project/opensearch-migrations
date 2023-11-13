package org.opensearch.migrations.replay.datatypes;

import lombok.AllArgsConstructor;
import lombok.ToString;

@AllArgsConstructor
@ToString
public class IndexedChannelInteraction {
    public final ISourceTrafficChannelKey channelKey;
    public final int index;
}
