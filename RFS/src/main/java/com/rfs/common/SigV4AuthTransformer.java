package com.rfs.common.http;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.opensearch.migrations.IHttpMessage;
import org.opensearch.migrations.aws.SigV4Signer;

import reactor.core.publisher.Mono;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

public class SigV4AuthTransformer implements AuthTransformer {
    private final SigV4Signer signer;

    public SigV4AuthTransformer(AwsCredentialsProvider credentialsProvider, String service, String region, String protocol, Supplier<Clock> timestampSupplier) {
        this.signer = new SigV4Signer(credentialsProvider, service, region, protocol, timestampSupplier);
    }

    @Override
    public Mono<TransformedRequest> transform(String method, String path, Map<String, List<String>> headers, Mono<String> body) {
        return body.flatMap(content -> {
            signer.consumeNextPayloadPart(java.nio.ByteBuffer.wrap(content.getBytes()));
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
                    return "HTTP/1.1";
                }

                @Override
                public Map<String, List<String>> headers() {
                    return headers;
                }
            });
            Map<String, List<String>> newHeaders = new HashMap<>(headers);
            newHeaders.putAll(signedHeaders);
            return Mono.just(new TransformedRequest(newHeaders, Mono.just(content)));
        });
    }
}
