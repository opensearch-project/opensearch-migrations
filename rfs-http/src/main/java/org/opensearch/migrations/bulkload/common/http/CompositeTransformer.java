package org.opensearch.migrations.bulkload.common.http;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Getter;
import reactor.core.publisher.Mono;

@AllArgsConstructor
@Getter
public class CompositeTransformer implements RequestTransformer {
    private final RequestTransformer firstTransformer;
    private final RequestTransformer secondTransformer;

    @Override
    public Mono<TransformedRequest> transform(
        String method,
        String path,
        Map<String, List<String>> headers,
        Mono<ByteBuffer> body
    ) {
        return firstTransformer.transform(method, path, headers, body)
            .flatMap(firstResult -> secondTransformer.transform(method,
                path,
                firstResult.getHeaders(),
                firstResult.getBody()
            ));
    }
}
