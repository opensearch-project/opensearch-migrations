package org.opensearch.migrations.bulkload.common.http;

import java.nio.ByteBuffer;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

public class FanOutCompositeTransformerTest {

    @Test
    public void testCompositeTransformer() {
        // Create mock transformers
        RequestTransformer firstTransformer = Mockito.mock(RequestTransformer.class);
        RequestTransformer secondTransformer = Mockito.mock(RequestTransformer.class);

        // Set up mock behavior
        TransformedRequest firstResult = new TransformedRequest(Collections.emptyMap(), Mono.empty());
        TransformedRequest finalResult = new TransformedRequest(Collections.emptyMap(), Mono.just(ByteBuffer.wrap("test".getBytes())));

        when(firstTransformer.transform(any(), any(), any(), any())).thenReturn(Mono.just(firstResult));
        when(secondTransformer.transform(any(), any(), any(), any())).thenReturn(Mono.just(finalResult));

        // Create CompositeTransformer
        CompositeTransformer compositeTransformer = new CompositeTransformer(firstTransformer, secondTransformer);

        // Test the transform method
        Mono<TransformedRequest> result = compositeTransformer.transform("GET", "/test", Collections.emptyMap(), Mono.empty());

        // Verify the result
        StepVerifier.create(result)
                .expectNext(finalResult)
                .verifyComplete();

        // Verify that both transformers were called
        Mockito.verify(firstTransformer).transform(any(), any(), any(), any());
        Mockito.verify(secondTransformer).transform(any(), any(), any(), any());
    }
}
