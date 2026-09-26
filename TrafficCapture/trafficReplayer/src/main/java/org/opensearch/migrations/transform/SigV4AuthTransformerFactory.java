package org.opensearch.migrations.transform;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.opensearch.migrations.IHttpMessage;
import org.opensearch.migrations.aws.SplittableSigV4Signer;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonRequestWithFaultingPayload;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

public class SigV4AuthTransformerFactory implements IAuthTransformerFactory {
    private final AwsCredentialsProvider credentialsProvider;
    private final String service;
    private final String region;
    private final String protocol;
    private final Supplier<Clock> timestampSupplier;

    public SigV4AuthTransformerFactory(
            AwsCredentialsProvider credentialsProvider,
            String service,
            String region,
            String protocol,
            Supplier<Clock> timestampSupplier) {
        this.credentialsProvider = credentialsProvider;
        this.service = service;
        this.region = region;
        this.protocol = protocol;
        this.timestampSupplier = timestampSupplier;
    }

    @Override
    public IAuthTransformer getAuthTransformer(HttpJsonRequestWithFaultingPayload httpMessage) {
        var signer = new SplittableSigV4Signer(credentialsProvider, service, region, protocol, timestampSupplier);
        return new IAuthTransformer.StreamingFullMessageTransformer() {
            @Override
            public void consumeNextPayloadPart(ByteBuffer contentChunk) {
                signer.consumeNextPayloadPart(contentChunk);
            }

            @Override
            public SignatureProducer finalizeContentHash() {
                var contentHash = signer.computeContentHash();
                var reentrantSigner = signer.createReentrantSigner(
                    contentHash, IHttpMessageAdapter.toIHttpMessage(httpMessage));
                return msg -> {
                    return reentrantSigner.sign();
                };
            }
        };
    }

    private interface IHttpMessageAdapter {
        static IHttpMessage toIHttpMessage(HttpJsonRequestWithFaultingPayload message) {
            return new IHttpMessage() {
                @Override
                public String method() {
                    return (String) message.get(JsonKeysForHttpMessage.METHOD_KEY);
                }

                @Override
                public String path() {
                    return (String) message.get(JsonKeysForHttpMessage.URI_KEY);
                }

                @Override
                public String protocol() {
                    return (String) message.get(JsonKeysForHttpMessage.PROTOCOL_KEY);
                }

                @Override
                public Optional<String> getFirstHeaderValueCaseInsensitive(String key) {
                    return Optional.ofNullable(message.headers().insensitiveGet(key))
                        .filter(l -> !l.isEmpty())
                        .map(l -> l.get(0));
                }

                @Override
                public Map<String, List<String>> headers() {
                    Map<String, Object> originalHeaders = message.headers();
                    Map<String, List<String>> convertedHeaders = new LinkedHashMap<>();

                    for (Map.Entry<String, Object> entry : originalHeaders.entrySet()) {
                        if (entry.getValue() instanceof List) {
                            @SuppressWarnings("unchecked")
                            List<String> values = (List<String>) entry.getValue();
                            convertedHeaders.put(entry.getKey(), values);
                        } else if (entry.getValue() != null) {
                            convertedHeaders.put(entry.getKey(), Collections.singletonList(entry.getValue().toString()));
                        }
                    }

                    return Collections.unmodifiableMap(convertedHeaders);
                }
            };
        }
    }
}
