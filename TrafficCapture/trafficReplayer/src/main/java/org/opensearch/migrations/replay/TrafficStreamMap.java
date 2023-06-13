package org.opensearch.migrations.replay;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * This object manages the lifecycle of Accumulation objects, creating new ones and (eventually, once implemented)
 * expiring old entries.  Callers are expected to modify the Accumulation values themselves, but to proxy every
 * distinct interaction through this class along with the timestamp of the observed interaction.  That (will)
 * allow this class to expunge outdated entries. Notice that a callback (or calling) mechanism still needs to
 * be implemented so that the calling context is aware that items have been expired.
 */
public class TrafficStreamMap {
    public TrafficStreamMap() {
        this.connectionToAccumulationMap = new ConcurrentHashMap<>();
    }

    ConcurrentHashMap<String, Accumulation> connectionToAccumulationMap;

    Accumulation get(String shardId, String id, Instant timestamp) {
        return connectionToAccumulationMap.get(id);
    }

    Accumulation getOrCreate(String shardId, String id, Instant timestamp) {
        return connectionToAccumulationMap.computeIfAbsent(id, k->new Accumulation());
    }

    Accumulation remove(String shardId, String id) {
        return connectionToAccumulationMap.remove(id);
    }

    Accumulation reset(String shardId, String id, Instant timestamp) {
        return connectionToAccumulationMap.put(id, new Accumulation());
    }

    Stream<Accumulation> values() {
        return connectionToAccumulationMap.values().stream();
    }

    void clear() {
        connectionToAccumulationMap.clear();
    }
}
