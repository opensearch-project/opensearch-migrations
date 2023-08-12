package org.opensearch.migrations.replay;


import java.security.MessageDigest;
import javax.xml.bind.DatatypeConverter;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.ArrayList;
import io.netty.buffer.ByteBuf;
import java.io.InputStream;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonMessageWithFaultingPayload;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;



public class SigV4Signer {
    public DefaultCredentialsProvider credentialsProvider;
    public Aws4Signer signer;
    public SdkHttpFullRequest.Builder httpRequestBuilder;
    public ArrayList<Byte> processedPayload;
    public HttpJsonMessageWithFaultingPayload message;

    public SigV4Signer(HttpJsonMessageWithFaultingPayload message, DefaultCredentialsProvider credentialsProvider, URI fullEndpoint) {
        this.message = message;
        this.credentialsProvider = credentialsProvider;
        this.signer = Aws4Signer.create();

        String host = fullEndpoint.getHost();
        String protocol = fullEndpoint.getScheme();

        this.httpRequestBuilder = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.fromValue(this.message.method()))
                .uri(URI.create(this.message.uri()))
                .protocol(protocol)
                .host(host);

        this.processedPayload = new ArrayList<>();

    }

    public void processNextPayload(ByteBuf payloadChunk) {

        // append new chunks to the existing payload
        byte[] chunkBytes = new byte[payloadChunk.readableBytes()];
        payloadChunk.readBytes(chunkBytes);
        for (byte b : chunkBytes) {
            this.processedPayload.add(b);
        }
    }

    public Map<String, List<String>> getSignatureheaders(String service, String region) throws Exception {

        byte[] payloadBytes = new byte[this.processedPayload.size()];
        for (int i = 0; i < this.processedPayload.size(); i++) {
            payloadBytes[i] = this.processedPayload.get(i);
        }

        InputStream payloadStream = ReplayUtils.byteArraysToInputStream(
                Collections.singletonList(payloadBytes)
        );
        this.httpRequestBuilder.contentStreamProvider(() -> payloadStream);
        String timeStamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").format(ZonedDateTime.now(ZoneOffset.UTC));
        httpRequestBuilder.appendHeader("Content-Type", "application/json");
        httpRequestBuilder.appendHeader("x-amz-date", timeStamp);
        String payloadHash = computeSha256Hash(payloadBytes);
        this.httpRequestBuilder.appendHeader("x-amz-content-sha256", payloadHash);

        SdkHttpFullRequest request = this.httpRequestBuilder.build(); // Is this the right time?

        SdkHttpFullRequest signedRequest = this.signer.sign(request, Aws4SignerParams.builder()
                .signingName(service)
                .signingRegion(Region.of(region))
                .awsCredentials(credentialsProvider.resolveCredentials())
                .build());

        System.out.println("Tried to sign: " + request.method().name() + ", " + request.getUri() +
                "region: " + region + " serviceName:" + service +
                " headers: ");
        for (var header : request.headers().entrySet()) {
            System.out.println(header.getKey() + ":" + header.getValue());
        }

        return signedRequest.headers();

    }

    public static String computeSha256Hash(byte[] payloadBytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(payloadBytes);
        return DatatypeConverter.printHexBinary(digest).toLowerCase();
    }
}


