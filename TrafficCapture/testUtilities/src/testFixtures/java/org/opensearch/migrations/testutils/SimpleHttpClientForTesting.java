package org.opensearch.migrations.testutils;

import java.io.InputStream;
import java.nio.charset.Charset;
import lombok.AllArgsConstructor;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.BasicHttpClientConnectionManager;
import org.apache.hc.client5.http.socket.ConnectionSocketFactory;
import org.apache.hc.client5.http.socket.PlainConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.TrustAllStrategy;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.ssl.SSLContexts;
import java.nio.charset.Charset;

import java.io.IOException;
import java.net.URI;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This is an HTTP client that is capable of making GET requests (developers are
 * encouraged to extend this) to either hosts that may be using TLS with a self=signed
 * certificate.
 */
public class SimpleHttpClientForTesting implements AutoCloseable {

    private final CloseableHttpClient httpClient;

    private static BasicHttpClientConnectionManager getInsecureTlsConnectionManager()
            throws NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
        final var sslContext = SSLContexts.custom()
                .loadTrustMaterial(null, new TrustAllStrategy())
                .build();

        return new BasicHttpClientConnectionManager(
                RegistryBuilder.<ConnectionSocketFactory>create()
                        .register("https", new SSLConnectionSocketFactory(sslContext, NoopHostnameVerifier.INSTANCE))
                        .register("http", new PlainConnectionSocketFactory())
                        .build()
        );
    }

    public SimpleHttpClientForTesting() {
        this(new BasicHttpClientConnectionManager());
    }

    public SimpleHttpClientForTesting(boolean useTlsAndInsecurelyInsteadOfClearText)
            throws NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
        this(useTlsAndInsecurelyInsteadOfClearText ?
                getInsecureTlsConnectionManager() : new BasicHttpClientConnectionManager());
    }

    private SimpleHttpClientForTesting(BasicHttpClientConnectionManager connectionManager) {
        httpClient = HttpClients.custom().setConnectionManager(connectionManager).build();
    }

    @AllArgsConstructor
    public static class PayloadAndContentType{
        public final InputStream contents;
        public final String contentType;
        //public final Charset charset;
    }

    public SimpleHttpResponse makeGetRequest(URI endpoint, Stream<Map.Entry<String,String>> requestHeaders)
            throws IOException {
        var request = new HttpGet(endpoint);
        requestHeaders.forEach(kvp->request.setHeader(kvp.getKey(), kvp.getValue()));
        var response = httpClient.execute(request);
        var responseBodyBytes = response.getEntity().getContent().readAllBytes();
        return new SimpleHttpResponse(
                Arrays.stream(response.getHeaders()).collect(Collectors.toMap(h->h.getName(), h->h.getValue())),
                responseBodyBytes, response.getReasonPhrase(), response.getCode());
    }

    public SimpleHttpResponse makePutRequest(URI endpoint, Stream<Map.Entry<String,String>> requestHeaders, PayloadAndContentType payloadAndContentType)
            throws IOException {
        var request = new HttpPut(endpoint);
        if (payloadAndContentType != null) {
            request.setEntity(new InputStreamEntity(payloadAndContentType.contents,
                    ContentType.create(payloadAndContentType.contentType)));
        }
        requestHeaders.forEach(kvp->request.setHeader(kvp.getKey(), kvp.getValue()));
        var response = httpClient.execute(request);
        var responseBodyBytes = response.getEntity().getContent().readAllBytes();
        return new SimpleHttpResponse(
                Arrays.stream(response.getHeaders()).collect(Collectors.toMap(h->h.getName(), h->h.getValue())),
                responseBodyBytes, response.getReasonPhrase(), response.getCode());
    }

    @Override
    public void close() throws IOException {
        httpClient.close();
    }
}


