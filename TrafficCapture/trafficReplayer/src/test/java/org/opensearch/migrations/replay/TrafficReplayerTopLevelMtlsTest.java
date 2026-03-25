package org.opensearch.migrations.replay;

import java.net.URI;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class TrafficReplayerTopLevelMtlsTest {

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
    public void testHttpsWithNoMtlsReturnsContext() {
        SslContext ctx = TrafficReplayerTopLevel.loadSslContext(
            URI.create("https://localhost:9200"), false, null, null, null);
        Assertions.assertNotNull(ctx);
    }

    @Test
    public void testHttpReturnsNull() {
        SslContext ctx = TrafficReplayerTopLevel.loadSslContext(
            URI.create("http://localhost:9200"), false, null, null, null);
        Assertions.assertNull(ctx);
    }

    @Test
    public void testHttpsWithCaCertReturnsContext() {
        SslContext ctx = TrafficReplayerTopLevel.loadSslContext(
            URI.create("https://localhost:9200"), false,
            ssc.certificate().getAbsolutePath(), null, null);
        Assertions.assertNotNull(ctx);
    }

    @Test
    public void testHttpsWithClientCertAndKeyReturnsContext() {
        SslContext ctx = TrafficReplayerTopLevel.loadSslContext(
            URI.create("https://localhost:9200"), false,
            null, ssc.certificate().getAbsolutePath(), ssc.privateKey().getAbsolutePath());
        Assertions.assertNotNull(ctx);
    }

    @Test
    public void testHttpsWithAllCertsReturnsContext() {
        SslContext ctx = TrafficReplayerTopLevel.loadSslContext(
            URI.create("https://localhost:9200"), false,
            ssc.certificate().getAbsolutePath(),
            ssc.certificate().getAbsolutePath(),
            ssc.privateKey().getAbsolutePath());
        Assertions.assertNotNull(ctx);
    }

    @Test
    public void testHttpsWithClientCertOnlyThrows() {
        Assertions.assertThrows(IllegalArgumentException.class, () ->
            TrafficReplayerTopLevel.loadSslContext(
                URI.create("https://localhost:9200"), false,
                null, ssc.certificate().getAbsolutePath(), null));
    }

    @Test
    public void testHttpsWithClientKeyOnlyThrows() {
        Assertions.assertThrows(IllegalArgumentException.class, () ->
            TrafficReplayerTopLevel.loadSslContext(
                URI.create("https://localhost:9200"), false,
                null, null, ssc.privateKey().getAbsolutePath()));
    }

    @Test
    public void testHttpWithMtlsCertsReturnsNull() {
        SslContext ctx = TrafficReplayerTopLevel.loadSslContext(
            URI.create("http://localhost:9200"), false,
            ssc.certificate().getAbsolutePath(),
            ssc.certificate().getAbsolutePath(),
            ssc.privateKey().getAbsolutePath());
        Assertions.assertNull(ctx);
    }

    @Test
    public void testBackwardsCompatibleOverloadDelegatesToFull() {
        SslContext ctx = TrafficReplayerTopLevel.loadSslContext(
            URI.create("https://localhost:9200"), false);
        Assertions.assertNotNull(ctx);
    }
}
