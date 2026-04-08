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

/**
 * A two-phase SigV4 signer that separates content hashing from signing.
 * <p>
 * Phase 1 (mutable): Call {@link #consumeNextPayloadPart} for each body chunk,
 * then {@link #computeContentHash()} to finalize the hash.
 * <p>
 * Phase 2 (immutable, reentrant): Call {@link #createSignatureProducer(IHttpMessage)}
 * to get a function that signs with a fresh timestamp on each invocation.
 */
@SuppressWarnings("java:S1874")
@Slf4j
public class SplittableSigV4Signer {
    private static final HashSet<String> AUTH_HEADERS_TO_PULL_WITH_PAYLOAD;
    private static final HashSet<String> AUTH_HEADERS_TO_PULL_NO_PAYLOAD;

    static {
        AUTH_HEADERS_TO_PULL_NO_PAYLOAD = new HashSet<>(Set.of("authorization", "x-amz-date", "x-amz-security-token"));
        AUTH_HEADERS_TO_PULL_WITH_PAYLOAD = Stream.concat(
            AUTH_HEADERS_TO_PULL_NO_PAYLOAD.stream(),
            Stream.of(SigV4Signer.AMZ_CONTENT_SHA_256)
        ).collect(Collectors.toCollection(HashSet::new));
    }

    private MessageDigest messageDigest;
    private final AwsCredentialsProvider credentialsProvider;
    private final String service;
    private final String region;
    private final String protocol;
    private final Supplier<Clock> timestampSupplier;

    public SplittableSigV4Signer(
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

    /**
     * Computes and returns the hex-encoded SHA-256 content hash. One-shot — consumes the digest.
     * Returns null if no payload parts were consumed.
     */
    public String computeContentHash() {
        if (messageDigest == null) {
            return null;
        }
        return BinaryUtils.toHex(messageDigest.digest());
    }

    /**
     * Creates an immutable, reentrant function that signs requests using the given content hash.
     * Each invocation produces fresh auth headers with a new timestamp.
     *
     * @param contentHash the pre-computed content hash (may be null if no body)
     * @param referenceMessage used to extract method, path, host, content-type for signing
     */
    public ReentrantSigner createReentrantSigner(String contentHash, IHttpMessage referenceMessage) {
        return new ReentrantSigner(
            credentialsProvider, service, region, protocol, timestampSupplier,
            contentHash, referenceMessage
        );
    }

    private static class AwsSignerWithPrecomputedContentHash extends BaseAws4Signer {
        @Override
        protected String calculateContentHash(
            SdkHttpFullRequest.Builder mutableRequest,
            Aws4SignerParams signerParams,
            SdkChecksum contentFlexibleChecksum
        ) {
            var contentChecksum = mutableRequest.headers().get(SigV4Signer.AMZ_CONTENT_SHA_256);
            return contentChecksum != null
                ? contentChecksum.get(0)
                : super.calculateContentHash(mutableRequest, signerParams, contentFlexibleChecksum);
        }
    }

    /**
     * Immutable, reentrant signer. Thread-safe. Each call to {@link #sign()} produces
     * fresh auth headers with a new timestamp.
     */
    public static class ReentrantSigner {
        private final AwsCredentialsProvider credentialsProvider;
        private final String service;
        private final String region;
        private final String protocol;
        private final Supplier<Clock> timestampSupplier;
        private final String contentHash;
        private final String method;
        private final String path;
        private final String host;
        private final String contentType;
        private final boolean hasPayload;

        ReentrantSigner(
            AwsCredentialsProvider credentialsProvider,
            String service, String region, String protocol,
            Supplier<Clock> timestampSupplier,
            String contentHash, IHttpMessage referenceMessage
        ) {
            this.credentialsProvider = credentialsProvider;
            this.service = service;
            this.region = region;
            this.protocol = protocol;
            this.timestampSupplier = timestampSupplier;
            this.contentHash = contentHash;
            this.hasPayload = contentHash != null;
            this.method = referenceMessage.method();
            this.path = referenceMessage.path();
            this.host = referenceMessage.getFirstHeaderValueCaseInsensitive("Host")
                .orElseThrow(() -> new IllegalArgumentException("Host header is missing"));
            this.contentType = referenceMessage.getFirstHeaderValueCaseInsensitive(SigV4Signer.CONTENT_TYPE)
                .orElse(null);
        }

        public Map<String, List<String>> sign() {
            var signer = new AwsSignerWithPrecomputedContentHash();
            var httpRequestBuilder = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.fromValue(method))
                .uri(URI.create(path))
                .protocol(protocol)
                .host(host);

            if (contentType != null) {
                httpRequestBuilder.appendHeader(SigV4Signer.CONTENT_TYPE, contentType);
            }
            if (contentHash != null) {
                httpRequestBuilder.appendHeader(SigV4Signer.AMZ_CONTENT_SHA_256, contentHash);
            }

            var signingParamsBuilder = Aws4SignerParams.builder()
                .signingName(service)
                .signingRegion(Region.of(region))
                .awsCredentials(credentialsProvider.resolveCredentials());
            if (timestampSupplier != null) {
                signingParamsBuilder.signingClockOverride(timestampSupplier.get());
            }

            var signedRequest = signer.sign(httpRequestBuilder.build(), signingParamsBuilder.build());

            var headersToReturn = hasPayload
                ? AUTH_HEADERS_TO_PULL_WITH_PAYLOAD
                : AUTH_HEADERS_TO_PULL_NO_PAYLOAD;
            return signedRequest.headers().entrySet().stream()
                .filter(kvp -> headersToReturn.contains(kvp.getKey().toLowerCase()))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        }
    }
}
