package org.opensearch.migrations.trafficcapture.proxyserver;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

import java.util.function.Supplier;

import com.beust.jcommander.ParameterException;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class FrontsideMtlsTest {

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
    public void testWithClientAuthRequiredReturnsEngineWithNeedClientAuth() throws SSLException {
        Supplier<SSLEngine> supplier = CaptureProxy.loadSslEngineFromPem(
            ssc.certificate().getAbsolutePath(),
            ssc.privateKey().getAbsolutePath(),
            ssc.certificate().getAbsolutePath(),
            true);
        SSLEngine engine = supplier.get();
        Assertions.assertTrue(engine.getNeedClientAuth());
    }

    @Test
    public void testWithoutClientAuthDoesNotRequireClientCert() throws SSLException {
        Supplier<SSLEngine> supplier = CaptureProxy.loadSslEngineFromPem(
            ssc.certificate().getAbsolutePath(),
            ssc.privateKey().getAbsolutePath(),
            ssc.certificate().getAbsolutePath(),
            false);
        SSLEngine engine = supplier.get();
        Assertions.assertFalse(engine.getNeedClientAuth());
    }

    @Test
    public void testWithoutTrustCertAndNoClientAuthSucceeds() throws SSLException {
        Supplier<SSLEngine> supplier = CaptureProxy.loadSslEngineFromPem(
            ssc.certificate().getAbsolutePath(),
            ssc.privateKey().getAbsolutePath(),
            null,
            false);
        SSLEngine engine = supplier.get();
        Assertions.assertFalse(engine.getNeedClientAuth());
    }

    @Test
    public void testRequireClientAuthWithoutTrustCertThrows() {
        Assertions.assertThrows(ParameterException.class, () ->
            CaptureProxy.loadSslEngineFromPem(
                ssc.certificate().getAbsolutePath(),
                ssc.privateKey().getAbsolutePath(),
                null,
                true));
    }
}
