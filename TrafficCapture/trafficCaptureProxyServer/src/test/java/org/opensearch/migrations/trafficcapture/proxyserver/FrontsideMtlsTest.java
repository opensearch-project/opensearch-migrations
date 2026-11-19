package org.opensearch.migrations.trafficcapture.proxyserver;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

import java.nio.file.Files;
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
    public void testWithInlineTrustCertPemAndClientAuthRequiredReturnsEngineWithNeedClientAuth() throws Exception {
        var trustCertPem = Files.readString(ssc.certificate().toPath());
        Supplier<SSLEngine> supplier = CaptureProxy.loadSslEngineFromPem(
            ssc.certificate().getAbsolutePath(),
            ssc.privateKey().getAbsolutePath(),
            null,
            trustCertPem,
            true);
        SSLEngine engine = supplier.get();
        Assertions.assertTrue(engine.getNeedClientAuth());
    }

    @Test
    public void testWithBothTrustCertFileAndInlineTrustCertPemThrows() throws Exception {
        var trustCertPem = Files.readString(ssc.certificate().toPath());
        Assertions.assertThrows(ParameterException.class, () ->
            CaptureProxy.loadSslEngineFromPem(
                ssc.certificate().getAbsolutePath(),
                ssc.privateKey().getAbsolutePath(),
                ssc.certificate().getAbsolutePath(),
                trustCertPem,
                true));
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
    public void testEngineOnlyEnablesTls12AndTls13() throws SSLException {
        SSLEngine engine = CaptureProxy.loadSslEngineFromPem(
            ssc.certificate().getAbsolutePath(),
            ssc.privateKey().getAbsolutePath(),
            null,
            false).get();
        Assertions.assertEquals(
            java.util.Set.of("TLSv1.2", "TLSv1.3"),
            java.util.Set.copyOf(java.util.Arrays.asList(engine.getEnabledProtocols())));
    }

    @Test
    public void testExplicitSslProtocolsLimitsEngineProtocols() throws SSLException {
        SSLEngine engine = CaptureProxy.loadSslEngineFromPem(
            ssc.certificate().getAbsolutePath(),
            ssc.privateKey().getAbsolutePath(),
            null,
            null,
            false,
            "TLSv1.2").get();
        Assertions.assertArrayEquals(new String[] { "TLSv1.2" }, engine.getEnabledProtocols());
    }

    @Test
    public void testParseSslProtocolsTrimsAndDeduplicates() {
        Assertions.assertArrayEquals(
            new String[] { "TLSv1.3", "TLSv1.2" },
            CaptureProxy.parseSslProtocols(" TLSv1.3 , TLSv1.2,TLSv1.3"));
    }

    @Test
    public void testParseSslProtocolsAcceptsLegacyWhenExplicit() {
        Assertions.assertArrayEquals(
            new String[] { "TLSv1.2", "TLSv1.1" },
            CaptureProxy.parseSslProtocols("TLSv1.2,TLSv1.1"));
    }

    @Test
    public void testParseSslProtocolsRejectsUnknownAndEmpty() {
        Assertions.assertThrows(ParameterException.class, () -> CaptureProxy.parseSslProtocols("SSLv3"));
        Assertions.assertThrows(ParameterException.class, () -> CaptureProxy.parseSslProtocols("TLSv1.2,bogus"));
        Assertions.assertThrows(ParameterException.class, () -> CaptureProxy.parseSslProtocols(" , "));
        Assertions.assertThrows(ParameterException.class, () -> CaptureProxy.parseSslProtocols(null));
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
