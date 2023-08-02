package org.opensearch.migrations.testutils;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.BasicHttpClientConnectionManager;
import org.apache.hc.client5.http.socket.ConnectionSocketFactory;
import org.apache.hc.client5.http.socket.PlainConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.TrustAllStrategy;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.ssl.SSLContexts;

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

    public SimpleHttpClientForTesting(boolean setupInsecureTls)
            throws NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
        this(setupInsecureTls ? getInsecureTlsConnectionManager() : new BasicHttpClientConnectionManager());
    }

    private SimpleHttpClientForTesting(BasicHttpClientConnectionManager connectionManager) {
        httpClient = HttpClients.custom().setConnectionManager(connectionManager).build();
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

    @Override
    public void close() throws IOException {
        httpClient.close();
    }
}
