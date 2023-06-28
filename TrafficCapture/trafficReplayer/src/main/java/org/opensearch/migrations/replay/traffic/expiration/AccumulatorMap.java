package org.opensearch.migrations.replay.traffic.expiration;

import org.opensearch.migrations.replay.Accumulation;

import java.util.concurrent.ConcurrentHashMap;

class AccumulatorMap extends ConcurrentHashMap<ScopedConnectionIdKey, Accumulation> {
}
