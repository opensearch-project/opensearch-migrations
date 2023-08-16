package org.opensearch.migrations.replay;


import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;

import io.netty.buffer.ByteBuf;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datahandlers.http.IHttpMessage;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;


@Slf4j
public class SigV4Signer {
    private AwsCredentialsProvider credentialsProvider;
    private Aws4Signer signer;
    private SdkHttpFullRequest.Builder httpRequestBuilder;
    private IHttpMessage httpMessagePreamble;
    private MessageDigest messageDigest;

    public SigV4Signer(IHttpMessage message, AwsCredentialsProvider credentialsProvider) {
        this.httpMessagePreamble = message;
        this.credentialsProvider = credentialsProvider;
        this.signer = Aws4Signer.create();

        this.httpRequestBuilder = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.fromValue(message.method()))
                .host(message.getFirstHeader("host"));

        try {
            this.messageDigest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public void processNextPayload(ByteBuffer payloadChunk) {
        messageDigest.update(payloadChunk);
    }

    public Map<String, List<String>> getSignatureheaders(String service, String region) throws Exception {
        String timeStamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").format(ZonedDateTime.now(ZoneOffset.UTC));
        httpRequestBuilder.appendHeader("Content-Type", "application/json");
        httpRequestBuilder.appendHeader("x-amz-date", timeStamp);
        String payloadHash = binaryToHex(messageDigest.digest());
        this.httpRequestBuilder.appendHeader("x-amz-content-sha256", payloadHash);

        SdkHttpFullRequest request = this.httpRequestBuilder.build(); // Is this the right time?

        SdkHttpFullRequest signedRequest = this.signer.sign(request, Aws4SignerParams.builder()
                .signingName(service)
                .signingRegion(Region.of(region))
                .awsCredentials(credentialsProvider.resolveCredentials())
                .build());

        log.info("Tried to sign: " + request.method().name() + ", " + request.getUri() +
                "region: " + region + " serviceName:" + service +
                " headers: ");
        for (var header : request.headers().entrySet()) {
            log.info(header.getKey() + ":" + header.getValue());
        }

        return signedRequest.headers();

    }

    public static String binaryToHex(byte[] bytesToEncode) throws Exception {
        return IntStream.range(0, bytesToEncode.length)
                .map(idx -> bytesToEncode[idx])
                .mapToObj(b -> Integer.toHexString(0xff & b))
                .map(h -> h.length() == 1 ? ("0" + h) : h)
                .collect(Collectors.joining());
    }
}


