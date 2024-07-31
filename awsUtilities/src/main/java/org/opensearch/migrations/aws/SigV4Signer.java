package org.opensearch.migrations.aws;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.opensearch.migrations.IHttpMessage;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4FamilyHttpSigner;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.HttpSigner;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;

@Slf4j
public class SigV4Signer {

    private AwsCredentialsProvider credentialsProvider;
    private String service;
    private String region;
    private String protocol;
    private Supplier<Clock> timestampSupplier; // for unit testing
    private ByteArrayOutputStream bodyStream;

    public SigV4Signer(
        AwsCredentialsProvider credentialsProvider,
        String service,
        String region,
        String protocol,
        Supplier<Clock> timestampSupplier
    ) {
        this.credentialsProvider = credentialsProvider;
        this.service = service;
        this.region = region;
        this.protocol = protocol;
        this.timestampSupplier = timestampSupplier;
        this.bodyStream = null;
    }

    private static void writeByteBufferToByteArrayOutputStream(ByteBuffer byteBuffer,
                                                               ByteArrayOutputStream outputStream) {
        var readBuffer = byteBuffer.duplicate();

        // If the ByteBuffer has a backing array, use it directly
        if (readBuffer.hasArray()) {
            outputStream.write(readBuffer.array(), readBuffer.position(), readBuffer.remaining());
        } else {
            // Otherwise, read bytes manually into a temporary array
            byte[] temp = new byte[readBuffer.remaining()];
            readBuffer.get(temp);
            outputStream.write(temp, 0, temp.length);
        }
    }

    public void consumeNextPayloadPart(ByteBuffer payloadChunk) {
        if (this.bodyStream == null) {
            this.bodyStream = new ByteArrayOutputStream();
        }
        writeByteBufferToByteArrayOutputStream(payloadChunk, this.bodyStream);
    }

    public Map<String, List<String>> finalizeSignature(IHttpMessage msg) {
        var stream = getSignatureHeadersViaSdk(msg);
        return stream.collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private Stream<Map.Entry<String, List<String>>> getSignatureHeadersViaSdk(IHttpMessage msg) {
        AwsV4HttpSigner signer = AwsV4HttpSigner.create();

        final AwsCredentials awsCredentials = credentialsProvider.resolveCredentials();

        final AwsCredentialsIdentity credentials = AwsBasicCredentials.create(
            awsCredentials.accessKeyId(),
            awsCredentials.secretAccessKey()
        );

        final String host = Optional.ofNullable(msg.getFirstHeader("Host")).orElseThrow(
            () -> new IllegalArgumentException("Cannot find host")
        );

        var uri = URI.create(protocol + "://" + host + msg.path());

        final SdkHttpRequest httpRequest = SdkHttpRequest.builder()
                .uri(uri)
                .method(SdkHttpMethod.fromValue(msg.method()))
                .headers(msg.headers())
                .build();

        final ContentStreamProvider requestPayload = bodyStream != null ?
            () -> new ByteArrayInputStream(bodyStream.toByteArray()) :
            null;

        return signer.sign(r -> r.identity(credentials)
            .request(httpRequest)
            .payload(requestPayload)
            .putProperty(AwsV4FamilyHttpSigner.SERVICE_SIGNING_NAME, this.service)
            .putProperty(AwsV4HttpSigner.REGION_NAME, this.region)
            .putProperty(HttpSigner.SIGNING_CLOCK, timestampSupplier.get())).request().headers().entrySet().stream();
    }
}
