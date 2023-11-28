package org.opensearch.migrations.replay.tracing;

import io.netty.util.AttributeKey;

import java.util.stream.Stream;

public interface WithAttributes {
    Stream<AttributeKey> getAttributeKeys();
}
