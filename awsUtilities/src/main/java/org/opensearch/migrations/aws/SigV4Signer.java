package org.opensearch.migrations.aws;

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

import org.opensearch.migrations.IHttpMessage;

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

/*
 * TODO: Figure out how to implement this with AwsV4HttpSigner given
 *  BaseAws4Signer/Aws4Signer is deprecated while keeping the streaming, non-buffering
 *  payload signing behavior.
 *  Also, think about signing all headers in the request
 */
@Slf4j
public class SigV4Signer {
    private static final HashSet<String> AUTH_HEADERS_TO_PULL_WITH_PAYLOAD;
    private static final HashSet<String> AUTH_HEADERS_TO_PULL_NO_PAYLOAD;

    public static final String AMZ_CONTENT_SHA_256 = "x-amz-content-sha256";
    public static final String CONTENT_TYPE = "Content-Type";

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

    public Map<String, List<String>> finalizeSignature(IHttpMessage msg) {
        var stream = getSignatureHeadersViaSdk(msg);
        return stream.collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
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

    private Stream<Map.Entry<String, List<String>>> getSignatureHeadersViaSdk(IHttpMessage msg) {
        var signer = new AwsSignerWithPrecomputedContentHash();
        var httpRequestBuilder = SdkHttpFullRequest.builder();

        httpRequestBuilder.method(SdkHttpMethod.fromValue(msg.method()))
            .uri(URI.create(msg.path()))
            .protocol(protocol)
            .host(msg.getFirstHeaderValueCaseInsensitive("Host").orElseThrow(
                () -> new IllegalArgumentException("Host header is missing")
            ));

        msg.getFirstHeaderValueCaseInsensitive(CONTENT_TYPE)
            .ifPresent(contentType -> httpRequestBuilder.appendHeader(CONTENT_TYPE, contentType));

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
