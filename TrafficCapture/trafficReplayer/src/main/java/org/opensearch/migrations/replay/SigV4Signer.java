package org.opensearch.migrations.replay;

import java.net.URI;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.opensearch.migrations.replay.datahandlers.http.HttpJsonMessageWithFaultingPayload;
import org.opensearch.migrations.transform.IAuthTransformer;
import org.opensearch.migrations.transform.IHttpMessage;

import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.signer.internal.BaseAws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.core.checksums.SdkChecksum;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.utils.BinaryUtils;

@Slf4j
public class SigV4Signer extends IAuthTransformer.StreamingFullMessageTransformer {
    private static final HashSet<String> AUTH_HEADERS_TO_PULL_WITH_PAYLOAD;
    private static final HashSet<String> AUTH_HEADERS_TO_PULL_NO_PAYLOAD;

    public static final String AMZ_CONTENT_SHA_256 = "x-amz-content-sha256";

    static {
        AUTH_HEADERS_TO_PULL_NO_PAYLOAD = new HashSet<>(Set.of("authorization", "x-amz-date", "x-amz-security-token"));
        AUTH_HEADERS_TO_PULL_WITH_PAYLOAD = Stream.concat(
            AUTH_HEADERS_TO_PULL_NO_PAYLOAD.stream(),
            Stream.of(AMZ_CONTENT_SHA_256)
        ).collect(Collectors.toCollection(HashSet::new));
    }

    private MessageDigest messageDigest;
    private AwsCredentialsProvider credentialsProvider;
    private String service;
    private String region;
    private String protocol;
    private Supplier<Clock> timestampSupplier; // for unit testing

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
    }

    @Override
    public ContextForAuthHeader transformType() {
        return ContextForAuthHeader.HEADERS_AND_CONTENT_PAYLOAD;
    }

    @Override
    public void consumeNextPayloadPart(ByteBuffer payloadChunk) {
        if (payloadChunk.remaining() <= 0) {
            return;
        }
        if (messageDigest == null) {
            try {
                this.messageDigest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw Lombok.sneakyThrow(e);
            }
        }
        messageDigest.update(payloadChunk);
    }

    @Override
    public void finalizeSignature(HttpJsonMessageWithFaultingPayload msg) {
        getSignatureHeadersViaSdk(msg).forEach(kvp -> msg.headers().put(kvp.getKey(), kvp.getValue()));
    }

    private static class AwsSignerWithPrecomputedContentHash extends BaseAws4Signer {
        @Override
        protected String calculateContentHash(
            SdkHttpFullRequest.Builder mutableRequest,
            Aws4SignerParams signerParams,
            SdkChecksum contentFlexibleChecksum
        ) {
            var contentChecksum = mutableRequest.headers().get(AMZ_CONTENT_SHA_256);
            return contentChecksum != null
                ? contentChecksum.get(0)
                : super.calculateContentHash(mutableRequest, signerParams, contentFlexibleChecksum);
        }
    }

    public Stream<Map.Entry<String, List<String>>> getSignatureHeadersViaSdk(IHttpMessage msg) {
        var signer = new AwsSignerWithPrecomputedContentHash();
        var httpRequestBuilder = SdkHttpFullRequest.builder();
        httpRequestBuilder.method(SdkHttpMethod.fromValue(msg.method()))
            .uri(URI.create(msg.path()))
            .protocol(protocol)
            .host(msg.getFirstHeader("host"));

        var contentType = msg.getFirstHeader(IHttpMessage.CONTENT_TYPE);
        if (contentType != null) {
            httpRequestBuilder.appendHeader("Content-Type", contentType);
        }
        if (messageDigest != null) {
            byte[] bytesToEncode = messageDigest.digest();
            String payloadHash = BinaryUtils.toHex(bytesToEncode);
            httpRequestBuilder.appendHeader(AMZ_CONTENT_SHA_256, payloadHash);
        }

        SdkHttpFullRequest request = httpRequestBuilder.build();

        var signingParamsBuilder = Aws4SignerParams.builder()
            .signingName(service)
            .signingRegion(Region.of(region))
            .awsCredentials(credentialsProvider.resolveCredentials());
        if (timestampSupplier != null) {
            signingParamsBuilder.signingClockOverride(timestampSupplier.get());
        }
        var signedRequest = signer.sign(request, signingParamsBuilder.build());

        var headersToReturn = messageDigest == null
            ? AUTH_HEADERS_TO_PULL_NO_PAYLOAD
            : AUTH_HEADERS_TO_PULL_WITH_PAYLOAD;
        return signedRequest.headers()
            .entrySet()
            .stream()
            .filter(kvp -> headersToReturn.contains(kvp.getKey().toLowerCase()));
    }
}
