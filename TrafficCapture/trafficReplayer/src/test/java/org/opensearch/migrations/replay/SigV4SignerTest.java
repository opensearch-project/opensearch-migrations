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

    private String getBasicAuthHeaderValue(String username, String password) {
        String auth = username + ":" + password;
        byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes(StandardCharsets.UTF_8));
        return "Basic " + new String(encodedAuth);
    }



}

