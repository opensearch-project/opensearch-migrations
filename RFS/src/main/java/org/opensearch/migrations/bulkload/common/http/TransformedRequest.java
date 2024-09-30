package org.opensearch.migrations.bulkload.common.http;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import lombok.Value;
import reactor.core.publisher.Mono;

@Value
public class TransformedRequest {
    Map<String, List<String>> headers;
    Mono<ByteBuffer> body;
}
