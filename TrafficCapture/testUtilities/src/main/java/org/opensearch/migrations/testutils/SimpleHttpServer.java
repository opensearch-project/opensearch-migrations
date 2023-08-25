package org.opensearch.migrations.testutils;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.function.Function;

/**
 * This class brings up an HTTP(s) server with its constructor that returns responses
 * based upon a simple Function that is passed to the constructor.  This class can support
 * TLS, but only with an auto-generated self-signed cert.
 */
public class SimpleHttpServer implements AutoCloseable {

    public static final String LOCALHOST = "localhost";
    public static final char[] KEYSTORE_PASSWORD = "".toCharArray();
    public static final int SOCKET_BACKLOG_SIZE = 10;
    protected final HttpServer httpServer;
    public final boolean useTls;

    public static class HttpFirstLine {
        public final String verb;
        public final URI path;
        public final String version;

        public HttpFirstLine(String verb, URI path, String version) {
            this.verb = verb;
            this.path = path;
            this.version = version;
        }
    }

    private static KeyStore buildKeyStoreForTesting() throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair keyPair = kpg.generateKeyPair();

        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null); // don't load from file, load a new key on the next line
        keyStore.setKeyEntry("selfsignedtestcert", keyPair.getPrivate(), KEYSTORE_PASSWORD,
                new X509Certificate[]{generateSelfSignedCertificate(keyPair)});
        return keyStore;
    }

    private static X509Certificate generateSelfSignedCertificate(KeyPair keyPair) throws OperatorCreationException, CertificateException {
        var startValidityInstant = Instant.now();
        var validityEndDate = Date.from(startValidityInstant.plus(Duration.ofHours(1)));
        var validityStartDate = Date.from(startValidityInstant);

        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA")
                .setProvider(new BouncyCastleProvider())
                .build(keyPair.getPrivate());
        var certBuilder = new JcaX509v3CertificateBuilder(
                new X500Name("CN=localhost"), // use your domain here
                new BigInteger(64, new SecureRandom()),
                validityStartDate,
                validityEndDate,
                new X500Name("CN=localhost"), // use your domain here
                keyPair.getPublic()
        );

        return new JcaX509CertificateConverter().getCertificate(certBuilder.build(signer));
    }

    private static HttpsServer createSecureServer(InetSocketAddress address)
            throws Exception {
        var httpsServer = HttpsServer.create(address, SOCKET_BACKLOG_SIZE);
        SSLContext sslContext = SSLContext.getInstance("TLS");

        KeyStore ks = buildKeyStoreForTesting();
        //ks.load(fis, KEYSTORE_PASSWORD);

        var kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(ks, KEYSTORE_PASSWORD);

        var tmf = TrustManagerFactory.getInstance("SunX509");
        tmf.init(ks);

        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
            public void configure(HttpsParameters params) {
                try {
                    SSLContext c = getSSLContext();
                    SSLEngine engine = c.createSSLEngine();
                    params.setNeedClientAuth(false);
                    params.setCipherSuites(engine.getEnabledCipherSuites());
                    params.setProtocols(engine.getEnabledProtocols());

                    var defaultSSLParameters = c.getDefaultSSLParameters();
                    params.setSSLParameters(defaultSSLParameters);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    System.out.println("Failed to create HTTPS port");
                }
            }
        });

        return httpsServer;
    }

    /**
     * @param port
     * @return the port upon successfully binding the server
     */
    public SimpleHttpServer(
            boolean useTls, int port,
            Function<HttpFirstLine, SimpleHttpResponse> uriToContentMapper) throws Exception {
        var addr = new InetSocketAddress(LOCALHOST, port);
        this.useTls = useTls;
        httpServer = useTls ? createSecureServer(addr) :
                HttpServer.create(addr, 0);
        httpServer.createContext("/", httpExchange -> {
            var requestToMatch =
                    new HttpFirstLine(httpExchange.getRequestMethod(),
                            httpExchange.getRequestURI(),
                            httpExchange.getProtocol());
            var headersAndPayload = uriToContentMapper.apply(requestToMatch);
            var responseHeaders = httpExchange.getResponseHeaders();
            for (var kvp : headersAndPayload.headers.entrySet()) {
                responseHeaders.set(kvp.getKey(), kvp.getValue());
            }

            httpExchange.sendResponseHeaders(200, 0);
            httpExchange.getResponseBody().write(headersAndPayload.payloadBytes);
            httpExchange.getResponseBody().flush();
            httpExchange.getResponseBody().close();
            httpExchange.close();
        });
        httpServer.start();
    }

    public int port() {
        return httpServer.getAddress().getPort();
    }

    public URI localhostEndpoint() {
        try {
            return new URI((useTls ? "https" : "http"), null, LOCALHOST, port(),"/",null, null);
        } catch (URISyntaxException e) {
            throw new RuntimeException("Error building URI", e);
        }
    }

    @Override
    public void close() throws Exception {
        httpServer.stop(0);
    }
}
