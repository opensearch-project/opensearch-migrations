package org.opensearch.migrations.replay;

import io.netty.buffer.Unpooled;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.nio.charset.Charset;
import io.netty.buffer.ByteBuf;
import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonMessageWithFaultingPayload;
import org.opensearch.migrations.replay.datahandlers.http.ListKeyAdaptingCaseInsensitiveHeadersMap;
import org.opensearch.migrations.replay.datahandlers.http.StrictCaseInsensitiveHttpHeadersMap;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import java.util.Map;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SigV4SignerTest {

    public static final String HOSTNAME_STRING = "YOUR_AOSS_ENDPOINT_WITHOUT_THE_PROTOCOL";
    private SigV4Signer signer;
    private static final String service = "aoss";
    private static final String region = "us-east-1";


    @BeforeEach
    void setup() {

    }

    @SneakyThrows
    @Test
    void testEmptyBodySignedRequestTest() {
        var nettyHandler = new NettyPacketToHttpConsumer(new NioEventLoopGroup(), new URI("YOUR_AOSS_ENDPOINT:443"),
                null, "");

        HttpJsonMessageWithFaultingPayload msg = new HttpJsonMessageWithFaultingPayload();
        msg.setMethod("PUT");
        msg.setUri("/ok92ok92");
        msg.setProtocol("https");
        var headers = new ListKeyAdaptingCaseInsensitiveHeadersMap(new StrictCaseInsensitiveHttpHeadersMap());
        headers.put("Host", HOSTNAME_STRING);
        msg.setHeaders(headers);

        var key_id = System.getenv("AWS_ACCESS_KEY_ID");
        var secret_key = System.getenv("AWS_SECRET_ACCESS_KEY");
        var token = System.getenv("AWS_SESSION_TOKEN");
        System.setProperty("aws.accessKeyId", key_id);
        System.setProperty("aws.secretAccessKey", secret_key);
        System.setProperty("aws.sessionToken", token);
        DefaultCredentialsProvider credentialsProvider = DefaultCredentialsProvider.create();
        var resolvedValue = credentialsProvider.resolveCredentials();

        signer = new SigV4Signer(msg, credentialsProvider);
        Map<String, List<String>> signedHeaders = signer.getSignatureheaders(service, region);

        var headersAsAString =  msg.headers().entrySet().stream()
                .map(kvp->String.format("%1$s: %2$s", kvp.getKey(), ((List<String>)kvp.getValue()).get(0)))
                .collect(Collectors.joining("\n"));

        var signedHeadersString = signedHeaders.entrySet().stream()
                .map(kvp -> String.format("%s: %s", kvp.getKey(), kvp.getValue().get(0)))
                .collect(Collectors.joining("\n"));

        String mergedHeadersString = headersAsAString + "\n" + signedHeadersString;

        byte[] arr = new byte[9999]; // YOUR REQUEST AS A BYTE ARRAY
        var requestString = String.format("%1$s %2$s %3$s\n" +
                        "%4$s\n\n",
                msg.method(), msg.uri(), HttpVersion.HTTP_1_1,
                mergedHeadersString);
        arr = requestString.getBytes();

        nettyHandler.consumeBytes(arr);
        AggregatedRawResponse response = nettyHandler.finalizeRequest().get();
        var uglyAnswer = response.responsePackets;
        System.out.println(uglyAnswer);
    }
}
