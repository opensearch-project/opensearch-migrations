package org.opensearch.migrations.trafficcapture.proxyserver;

import java.net.URI;

import javax.net.ssl.SSLException;

import com.beust.jcommander.ParameterException;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class BacksideSslContextMtlsTest {

    private static SelfSignedCertificate ssc;

    @BeforeAll
    static void setUp() throws Exception {
        ssc = new SelfSignedCertificate();
    }

    @AfterAll
    static void tearDown() {
        ssc.delete();
    }

    @Test
    public void testHttpsWithNoMtlsReturnsContext() throws SSLException {
        SslContext ctx = CaptureProxy.loadBacksideSslContext(
            URI.create("https://localhost:9200"), false, null, null, null);
        Assertions.assertNotNull(ctx);
    }

    @Test
    public void testHttpReturnsNull() throws SSLException {
        SslContext ctx = CaptureProxy.loadBacksideSslContext(
            URI.create("http://localhost:9200"), false, null, null, null);
        Assertions.assertNull(ctx);
    }

    @Test
    public void testHttpsWithCaCertReturnsContext() throws SSLException {
        SslContext ctx = CaptureProxy.loadBacksideSslContext(
            URI.create("https://localhost:9200"), false,
            ssc.certificate().getAbsolutePath(), null, null);
        Assertions.assertNotNull(ctx);
    }

    @Test
    public void testHttpsWithClientCertAndKeyReturnsContext() throws SSLException {
        SslContext ctx = CaptureProxy.loadBacksideSslContext(
            URI.create("https://localhost:9200"), false,
            null, ssc.certificate().getAbsolutePath(), ssc.privateKey().getAbsolutePath());
        Assertions.assertNotNull(ctx);
    }

    @Test
    public void testHttpsWithAllCertsReturnsContext() throws SSLException {
        SslContext ctx = CaptureProxy.loadBacksideSslContext(
            URI.create("https://localhost:9200"), false,
            ssc.certificate().getAbsolutePath(),
            ssc.certificate().getAbsolutePath(),
            ssc.privateKey().getAbsolutePath());
        Assertions.assertNotNull(ctx);
    }

    @Test
    public void testHttpsWithClientCertOnlyThrows() {
        Assertions.assertThrows(ParameterException.class, () ->
            CaptureProxy.loadBacksideSslContext(
                URI.create("https://localhost:9200"), false,
                null, ssc.certificate().getAbsolutePath(), null));
    }

    @Test
    public void testHttpsWithClientKeyOnlyThrows() {
        Assertions.assertThrows(ParameterException.class, () ->
            CaptureProxy.loadBacksideSslContext(
                URI.create("https://localhost:9200"), false,
                null, null, ssc.privateKey().getAbsolutePath()));
    }

    @Test
    public void testHttpWithMtlsCertsReturnsNull() throws SSLException {
        SslContext ctx = CaptureProxy.loadBacksideSslContext(
            URI.create("http://localhost:9200"), false,
            ssc.certificate().getAbsolutePath(),
            ssc.certificate().getAbsolutePath(),
            ssc.privateKey().getAbsolutePath());
        Assertions.assertNull(ctx);
    }

    @Test
    public void testInsecureWithMtlsCertsReturnsContext() throws SSLException {
        SslContext ctx = CaptureProxy.loadBacksideSslContext(
            URI.create("https://localhost:9200"), true,
            null, ssc.certificate().getAbsolutePath(), ssc.privateKey().getAbsolutePath());
        Assertions.assertNotNull(ctx);
    }
}
