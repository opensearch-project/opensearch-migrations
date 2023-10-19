package org.opensearch.migrations.replay.datatypes;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
public class TrafficStreamKeyWithRequestOffset {

    @Getter
    private final ITrafficStreamKey trafficStreamKey;
    @Getter
    private final int requestIndexOffsetAtFirstObservation;
}
