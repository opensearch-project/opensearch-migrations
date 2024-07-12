package org.opensearch.migrations.replay.traffic.expiration;

import java.util.concurrent.ConcurrentHashMap;

import org.opensearch.migrations.replay.Accumulation;

public class AccumulatorMap extends ConcurrentHashMap<ScopedConnectionIdKey, Accumulation> {}
