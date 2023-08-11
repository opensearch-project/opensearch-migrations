package org.opensearch.migrations.replay;

import io.netty.buffer.Unpooled;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import javax.net.ssl.SSLException;
import lombok.SneakyThrows;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Base64;
import java.nio.charset.Charset;
import io.netty.buffer.ByteBuf;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonMessageWithFaultingPayload;
import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleHttpClientForTesting;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import java.util.Map;


import static org.junit.jupiter.api.Assertions.assertEquals;

class SigV4SignerTest {

    //public static final String HOSTNAME_STRING = "YOUR_HOST";
    private SigV4Signer signer;
    private static final String service = "es";
    private static final String region = "us-east-1";

    private SimpleHttpClientForTesting client;

    @SneakyThrows
    @BeforeEach
    void setup() {
        client = new SimpleHttpClientForTesting(false);
        HttpJsonMessageWithFaultingPayload msg = new HttpJsonMessageWithFaultingPayload();
        msg.setMethod("PUT");
        msg.setUri("/ok92worked");
        msg.setProtocol("https");
        var key_id = System.getenv("AWS_ACCESS_KEY_ID");
        var secret_key = System.getenv("AWS_SECRET_ACCESS_KEY");
        var token = System.getenv("AWS_SESSION_TOKEN");
        System.setProperty("aws.accessKeyId", key_id);
        System.setProperty("aws.secretAccessKey", secret_key);
        System.setProperty("aws.sessionToken", token);

        signer = new SigV4Signer(msg, DefaultCredentialsProvider.create());
    }

    private static SslContext loadSslContext(URI serverUri, boolean allowInsecureConnections) throws SSLException {
        if (serverUri.getScheme().toLowerCase().equals("https")) {
            var sslContextBuilder = SslContextBuilder.forClient();
            if (allowInsecureConnections) {
                sslContextBuilder.trustManager(InsecureTrustManagerFactory.INSTANCE);
            }
            return sslContextBuilder.build();
        } else {
            return null;
        }
    }

    @SneakyThrows
    @Test
    void testEmptyBodySignedRequestTestGET() {
        client = new SimpleHttpClientForTesting(false);
        HttpJsonMessageWithFaultingPayload msg = new HttpJsonMessageWithFaultingPayload();
        msg.setMethod("GET");
        msg.setUri("/>>YOUR_URI<<");
        msg.setProtocol("https");
        var key_id = System.getenv("AWS_ACCESS_KEY_ID");
        var secret_key = System.getenv("AWS_SECRET_ACCESS_KEY");
        var token = System.getenv("AWS_SESSION_TOKEN");
        System.setProperty("aws.accessKeyId", key_id);
        System.setProperty("aws.secretAccessKey", secret_key);
        System.setProperty("aws.sessionToken", token);

        signer = new SigV4Signer(msg, DefaultCredentialsProvider.create());

        URI endpoint = new URI(">>YOUR_ENDPOINT+URI<<");

        Map<String, List<String>> signedHeaders = signer.getSignatureheaders("es", "us-east-1");

        // Convert to List<Map.Entry<String, String>>
        List<Map.Entry<String, String>> headerEntries = signedHeaders.entrySet().stream()
                .map(kvp -> Map.entry(kvp.getKey(), kvp.getValue().get(0)))
                .collect(Collectors.toList());

        // Print headers before
        headerEntries.forEach(header -> System.out.println(header.getKey() + ": " + header.getValue()));

        // Convert list back to stream
        Stream<Map.Entry<String, String>> requestHeaders = headerEntries.stream();

        // Making the request
        SimpleHttpResponse response = client.makeGetRequest(endpoint, requestHeaders);

        // Print headers after
        headerEntries.forEach(header -> System.out.println(header.getKey() + ": " + header.getValue()));

        assertEquals(200, response.statusCode);


    }

    @SneakyThrows
    @Test
    void testEmptyBodySignedRequestTestPUT() throws Exception {
        client = new SimpleHttpClientForTesting(false);
        HttpJsonMessageWithFaultingPayload msg = new HttpJsonMessageWithFaultingPayload();
        msg.setMethod("PUT");
        msg.setUri("/YOUR_URI");
        msg.setProtocol("https");
        var key_id = System.getenv("AWS_ACCESS_KEY_ID");
        var secret_key = System.getenv("AWS_SECRET_ACCESS_KEY");
        var token = System.getenv("AWS_SESSION_TOKEN");
        System.setProperty("aws.accessKeyId", key_id);
        System.setProperty("aws.secretAccessKey", secret_key);
        System.setProperty("aws.sessionToken", token);

        signer = new SigV4Signer(msg, DefaultCredentialsProvider.create());

        URI endpoint = new URI(">>YOUR_ENDPOINT+URI<<");


        ByteBuf payloadChunk1 = Unpooled.copiedBuffer("{\"index\" : {\"number_", StandardCharsets.UTF_8);
        ByteBuf payloadChunk2 = Unpooled.copiedBuffer("of_replicas\" : 2}}", StandardCharsets.UTF_8);
        payloadChunk1.readerIndex(0);
        signer.processNextPayload(payloadChunk1);
        payloadChunk1.readerIndex(0);
        signer.processNextPayload(payloadChunk2);

        //For Debugging
        payloadChunk1.readerIndex(0);
        String payloadChunk1Str = payloadChunk1.toString(StandardCharsets.UTF_8);
        payloadChunk2.readerIndex(0);
        String payloadChunk2Str = payloadChunk2.toString(StandardCharsets.UTF_8);

        System.out.println("Payload Chunk 1: " + payloadChunk1Str);
        System.out.println("Payload Chunk 2: " + payloadChunk2Str);

        // Get the signed headers
        Map<String, List<String>> signedHeaders = signer.getSignatureheaders("es", "us-east-1");

        // Convert to List<Map.Entry<String, String>>
        List<Map.Entry<String, String>> headerEntries = signedHeaders.entrySet().stream()
                .map(kvp -> Map.entry(kvp.getKey(), kvp.getValue().get(0)))
                .collect(Collectors.toList());

        headerEntries.forEach(header -> System.out.println(header.getKey() + ": " + header.getValue()));

        // Convert list back to stream
        Stream<Map.Entry<String, String>> requestHeaders = headerEntries.stream();

        // Creating PayloadAndContentType with content-type from processed payload
        byte[] processedPayloadBytes = new byte[signer.processedPayload.size()];
        for (int i = 0; i < signer.processedPayload.size(); i++) {
            processedPayloadBytes[i] = signer.processedPayload.get(i);
        }

        String payloadString = new String(processedPayloadBytes, StandardCharsets.UTF_8);
        System.out.println("Payload: " + payloadString);

        SimpleHttpClientForTesting.PayloadAndContentType payload = new SimpleHttpClientForTesting.PayloadAndContentType(new ByteArrayInputStream(processedPayloadBytes), "application/json");


        // Before sending the request... For debugging
        System.out.println("Request Method: " + msg.method()); // This might vary based on your class's method.
        System.out.println("Endpoint: " + msg.uri());

        // Print headers
        System.out.println("Headers:");
        for (Map.Entry<String, List<String>> header : signedHeaders.entrySet()) {
            System.out.println(header.getKey() + ": " + header.getValue());
        }

        // Print payload/body
        ByteBuf combinedPayload = Unpooled.wrappedBuffer(payloadChunk1, payloadChunk2);
        String payloadStringNew = combinedPayload.toString(Charset.defaultCharset());
        System.out.println("Payload:");
        System.out.println(payloadStringNew);


        // Making the request
        SimpleHttpResponse response = client.makePutRequest(endpoint, requestHeaders, payload);

        headerEntries.forEach(header -> System.out.println(header.getKey() + ": " + header.getValue()));

        assertEquals(200, response.statusCode);
    }


    private String getBasicAuthHeaderValue(String username, String password) {
        String auth = username + ":" + password;
        byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes(StandardCharsets.UTF_8));
        return "Basic " + new String(encodedAuth);
    }



}

