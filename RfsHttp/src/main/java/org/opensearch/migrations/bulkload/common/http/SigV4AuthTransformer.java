package org.opensearch.migrations.bulkload.common.http;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.opensearch.migrations.IHttpMessage;
import org.opensearch.migrations.aws.SigV4Signer;

import lombok.AllArgsConstructor;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

@AllArgsConstructor
public class SigV4AuthTransformer implements RequestTransformer {
    AwsCredentialsProvider credentialsProvider;
    String service;
    String region;
    String protocol;
    Supplier<Clock> timestampSupplier;

    @Override
    public Mono<TransformedRequest> transform(String method, String path, Map<String, List<String>> headers, Mono<ByteBuffer> body) {
        var signer = new SigV4Signer(credentialsProvider, service, region, protocol, timestampSupplier);
        return body
            .doOnNext(b -> signer.consumeNextPayloadPart(b.duplicate()))
            .singleOptional()
            .map(contentOp -> {
            Map<String, List<String>> signedHeaders = signer.finalizeSignature(new IHttpMessage() {
                @Override
                public String method() {
                    return method;
                }

                @Override
                public String path() {
                    return path;
                }

                @Override
                public String protocol() {
                    throw new UnsupportedOperationException("Protocol based transformation not supported");
                }

                @Override
                public Map<String, List<String>> headers() {
                    return headers;
                }
            });
            var newHeaders = new HashMap<>(headers);
            newHeaders.putAll(signedHeaders);
            return new TransformedRequest(newHeaders, Mono.justOrEmpty(contentOp));
        });
    }
}
