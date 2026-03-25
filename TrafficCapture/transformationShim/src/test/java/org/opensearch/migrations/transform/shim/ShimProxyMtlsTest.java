package org.opensearch.migrations.transform.shim;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.transform.shim.validation.Target;

import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class ShimProxyMtlsTest {

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
    public void testConstructorWithMtlsCertsDoesNotThrow() throws Exception {
        var targets = Map.of("alpha", new Target("alpha", URI.create("http://localhost:9200")));
        var proxy = new ShimProxy(
            0, targets, "alpha", null, List.of(),
            null, false, Duration.ofSeconds(5), 1024 * 1024,
            ssc.certificate().getAbsolutePath(),
            ssc.certificate().getAbsolutePath(),
            ssc.privateKey().getAbsolutePath());
        Assertions.assertNotNull(proxy);
        proxy.stop();
    }

    @Test
    public void testConstructorWithNullCertsDoesNotThrow() throws Exception {
        var targets = Map.of("alpha", new Target("alpha", URI.create("http://localhost:9200")));
        var proxy = new ShimProxy(
            0, targets, "alpha", null, List.of(),
            null, false, Duration.ofSeconds(5), 1024 * 1024,
            null, null, null);
        Assertions.assertNotNull(proxy);
        proxy.stop();
    }

    @Test
    public void testConstructorWithClientCertOnlyThrows() {
        var targets = Map.of("alpha", new Target("alpha", URI.create("https://localhost:9200")));
        Assertions.assertThrows(IllegalStateException.class, () ->
            new ShimProxy(
                0, targets, "alpha", null, List.of(),
                null, false, Duration.ofSeconds(5), 1024 * 1024,
                null, ssc.certificate().getAbsolutePath(), null));
    }

    @Test
    public void testConstructorWithClientKeyOnlyThrows() {
        var targets = Map.of("alpha", new Target("alpha", URI.create("https://localhost:9200")));
        Assertions.assertThrows(IllegalStateException.class, () ->
            new ShimProxy(
                0, targets, "alpha", null, List.of(),
                null, false, Duration.ofSeconds(5), 1024 * 1024,
                null, null, ssc.privateKey().getAbsolutePath()));
    }

    @Test
    public void testOriginalConstructorStillWorks() throws Exception {
        var targets = Map.of("alpha", new Target("alpha", URI.create("http://localhost:9200")));
        var proxy = new ShimProxy(
            0, targets, "alpha", null, List.of(),
            null, false, Duration.ofSeconds(5), 1024 * 1024);
        Assertions.assertNotNull(proxy);
        proxy.stop();
    }
}
