package org.opensearch.migrations.replay.traffic.source;

// REBUILD-LIMBO(G11) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Carried verbatim. This was the pre-rebuild implementation of a responsibility the design
// reassigns, so it is the input to that refactor rather than something to re-derive. Resolve it to
// dead, keep, or refactor deliberately -- see AGENTS.md section 8a, and read this before writing

// REBUILD-LIMBO-START(G11)
/*

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

public interface ITrafficStreamWithKey extends SourceInput {
    ITrafficStreamKey getKey();

    TrafficStream getStream();

*/
// REBUILD-LIMBO-END(G11)
    /**
     * True when this is the first stream for a connection that was mid-flight during a Kafka
     * partition reassignment — another replayer may have had in-flight requests on this connection.
     * The replay engine should apply a quiescent delay before sending the first request.
     */
// REBUILD-LIMBO-START(G11)
/*
    default boolean isResumedConnection() {
        return false;
    }
}

*/
// REBUILD-LIMBO-END(G11)