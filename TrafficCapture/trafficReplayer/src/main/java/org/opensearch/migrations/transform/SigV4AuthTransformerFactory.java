package org.opensearch.migrations.transform;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.util.function.Supplier;

import org.opensearch.migrations.aws.SigV4Signer;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonMessageWithFaultingPayload;
import org.opensearch.migrations.replay.datahandlers.http.helpers.IHttpMessageAdapter;

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
    public IAuthTransformer getAuthTransformer(HttpJsonMessageWithFaultingPayload httpMessage) {
        SigV4Signer signer = new SigV4Signer(credentialsProvider, service, region, protocol, timestampSupplier);
        return new IAuthTransformer.StreamingFullMessageTransformer() {
            @Override
            public void consumeNextPayloadPart(ByteBuffer contentChunk) {
                signer.consumeNextPayloadPart(contentChunk);
            }

            @Override
            public void finalizeSignature(HttpJsonMessageWithFaultingPayload msg) {
                var signatureHeaders = signer.finalizeSignature(IHttpMessageAdapter.toIHttpMessage(httpMessage));
                msg.headers().putAll(signatureHeaders);
            }
        };
    }
}
