package org.opensearch.migrations.bulkload.common.http;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import reactor.core.publisher.Mono;

public interface RequestTransformer {
    Mono<TransformedRequest> transform(String method, String path, Map<String, List<String>> headers, Mono<ByteBuffer> body);
}
