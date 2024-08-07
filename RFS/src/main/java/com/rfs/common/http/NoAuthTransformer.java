package com.rfs.common.http;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import reactor.core.publisher.Mono;

public class NoAuthTransformer implements RequestTransformer {
    public static NoAuthTransformer INSTANCE = new NoAuthTransformer();

    private NoAuthTransformer() {}

    @Override
    public Mono<TransformedRequest> transform(String method, String path, Map<String, List<String>> headers, Mono<ByteBuffer> body) {
        return Mono.just(new TransformedRequest(new HashMap<>(headers), body));
    }
}
