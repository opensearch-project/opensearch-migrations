package org.opensearch.migrations.replay;

import org.opensearch.migrations.replay.ReplayUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonMessageWithFaultingPayload;
import org.opensearch.migrations.replay.datahandlers.http.IHttpMessageMetadata;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;



public class SigV4Signer {
    public IHttpMessageMetadata messageMetadata;

    public DefaultCredentialsProvider credentialsProvider;
    public Aws4Signer signer;
    public SdkHttpFullRequest.Builder httpRequestBuilder;
    public StringBuilder processedPayload;
    public HttpJsonMessageWithFaultingPayload message;


    public SigV4Signer(HttpJsonMessageWithFaultingPayload message, DefaultCredentialsProvider credentialsProvider) {
        this.message = message;
        this.credentialsProvider = credentialsProvider;
        this.credentialsProvider = DefaultCredentialsProvider.create();
        this.signer = Aws4Signer.create();
        var host = ((List<String>) (message.headers().get("HOST"))).get(0);
        this.httpRequestBuilder = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.fromValue(this.message.method()))
                .uri(URI.create(this.message.uri()))
                .protocol(this.message.protocol())
                .host(host)
                .appendHeader("host", host);
        this.processedPayload = new StringBuilder();

    }

    public void processNextPayload(ByteBuf payloadChunk) {

        // append new chunks to the existing payload
        this.processedPayload.append(payloadChunk.toString(StandardCharsets.UTF_8));

        // Request builder action here?
    }

    public Map<String, List<String>> getSignatureheaders(String service, String region) {

        // #TODO: NIT: Aws4Signer signer = Aws4Signer.create(); Should Aws4Sginer variable exist here or in the class itself and call it as this.signer?

        InputStream payloadStream = ReplayUtils.byteArraysToInputStream(
                Collections.singletonList(this.processedPayload.toString().getBytes(StandardCharsets.UTF_8))
        );
        this.httpRequestBuilder.contentStreamProvider(() -> payloadStream);

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
}


