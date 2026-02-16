package org.opensearch.migrations.bulkload.common.http;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import reactor.core.publisher.Mono;

@AllArgsConstructor
public class NoAuthTransformer implements RequestTransformer {

    @Override
    public Mono<TransformedRequest> transform(String method, String path, Map<String, List<String>> headers, Mono<ByteBuffer> body) {
        return Mono.just(new TransformedRequest(new HashMap<>(headers), body));
    }
}
