package org.opensearch.migrations.replay;


import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonMessageWithFaultingPayload;
import org.opensearch.migrations.transform.IAuthTransformer;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;


@Slf4j
public class SigV4Signer extends IAuthTransformer.StreamingFullMessageTransformer {
    private MessageDigest messageDigest;
    private AwsCredentialsProvider credentialsProvider;
    private String service;
    private String region;

    public SigV4Signer(AwsCredentialsProvider credentialsProvider, String service, String region) {
        this.credentialsProvider = credentialsProvider;
        this.service = service;
        this.region = region;

        try {
            this.messageDigest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public ContextForAuthHeader transformType() {
        return ContextForAuthHeader.HEADERS_AND_CONTENT_PAYLOAD;
    }

    @Override
    public void consumeNextPayloadPart(ByteBuffer payloadChunk) {
        messageDigest.update(payloadChunk);
    }

    @Override
    public void finalize(HttpJsonMessageWithFaultingPayload msg) {
        getSignatureHeadersViaSdk(msg).forEach(kvp -> msg.headers().put(kvp.getKey(), kvp.getValue()));
    }

    public Stream<Map.Entry<String, List<String>>> getSignatureHeadersViaSdk(HttpJsonMessageWithFaultingPayload msg) {
        var signer = Aws4Signer.create();
        var httpRequestBuilder = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.fromValue(msg.method()))
                .host(msg.getFirstHeader("host"));

        String timeStamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").format(ZonedDateTime.now(ZoneOffset.UTC));
        httpRequestBuilder.appendHeader("Content-Type", "application/json");
        httpRequestBuilder.appendHeader("x-amz-date", timeStamp);
        String payloadHash = binaryToHex(messageDigest.digest());
        httpRequestBuilder.appendHeader("x-amz-content-sha256", payloadHash);

        SdkHttpFullRequest request = httpRequestBuilder.build(); // Is this the right time?

        SdkHttpFullRequest signedRequest = signer.sign(request, Aws4SignerParams.builder()
                .signingName(service)
                .signingRegion(Region.of(region))
                .awsCredentials(credentialsProvider.resolveCredentials())
                .build());

        log.info("Tried to sign: " + request.method().name() + ", " + request.getUri() +
                "region: " + region + " serviceName:" + service +
                " headers: ");

        return signedRequest.headers().entrySet().stream().filter(kvp->
                kvp.getKey().equals(""));
    }


    public static String binaryToHex(byte[] bytesToEncode) {
        return IntStream.range(0, bytesToEncode.length)
                .map(idx -> bytesToEncode[idx])
                .mapToObj(b -> Integer.toHexString(0xff & b))
                .map(h -> h.length() == 1 ? ("0" + h) : h)
                .collect(Collectors.joining());
    }
}


