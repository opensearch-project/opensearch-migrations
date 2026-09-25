package org.opensearch.migrations.replay.kafka;

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
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

*/
// REBUILD-LIMBO-END(G11)
/**
 * Synthetic record injected by KafkaTrafficCaptureSource when a partition is truly lost
 * (revoked and not reassigned back to this consumer). Signals the accumulator to close
 * the connection with ReconstructionStatus.TRAFFIC_SOURCE_READER_INTERRUPTED rather than a source-side close.
 * Does not carry a real Kafka offset and must not trigger a Kafka commit.
 */
// REBUILD-LIMBO-START(G11)
/*
@RequiredArgsConstructor
@Getter
public class TrafficSourceReaderInterruptedClose implements ITrafficStreamWithKey {
    private final ITrafficStreamKey key;

*/
// REBUILD-LIMBO-END(G11)
// REBUILD-TRACE-START(G8,source): retain through the rebuild; remove in final pre-merge cleanup.
// TrafficSourceReaderInterruptedClose.getStream -> RETIRED
// Typed generation cancellation and cleanup replace the synthetic record; G11 retains process teardown only.
// REBUILD-TRACE-END(G8,source)
// REBUILD-LIMBO-START(G11)
/*
    @Override
    public TrafficStream getStream() {
        // Stub — accumulator detects this type before accessing the stream
        return TrafficStream.getDefaultInstance();
    }
}

*/
// REBUILD-LIMBO-END(G11)
