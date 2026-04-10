package org.opensearch.migrations.trafficcapture.proxyserver;

import javax.net.ssl.SSLEngine;

import java.util.function.Supplier;

import com.beust.jcommander.ParameterException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class PemSslConfigurationTest {

    /**
     * Generate a self-signed cert + key pair using keytool and openssl-equivalent Netty utilities.
     * For test purposes, we use Netty's SelfSignedCertificate which generates PEM files.
     */
    private io.netty.handler.ssl.util.SelfSignedCertificate createSelfSignedCert() throws Exception {
        return new io.netty.handler.ssl.util.SelfSignedCertificate();
    }

    @Test
    public void testPemSslEngineLoadsSuccessfully() throws Exception {
        var ssc = createSelfSignedCert();
        Supplier<SSLEngine> supplier = CaptureProxy.loadSslEngineFromPem(
                    ssc.certificate().getAbsolutePath(),
                    ssc.privateKey().getAbsolutePath(),
                    null,
                    false
                );
        Assertions.assertNotNull(supplier);
        SSLEngine engine = supplier.get();
        Assertions.assertNotNull(engine);
        ssc.delete();
    }

    @Test
    public void testPemSslEngineWithTrustCert() throws Exception {
        var ssc = createSelfSignedCert();
        // Use the same cert as the trust cert for testing
        Supplier<SSLEngine> supplier = CaptureProxy.loadSslEngineFromPem(
                    ssc.certificate().getAbsolutePath(),
                    ssc.privateKey().getAbsolutePath(),
                    ssc.certificate().getAbsolutePath(),
                    false
                );
        Assertions.assertNotNull(supplier);
        SSLEngine engine = supplier.get();
        Assertions.assertNotNull(engine);
        ssc.delete();
    }

    @Test
    public void testPemSslEngineWithEmptyTrustCertIsIgnored() throws Exception {
        var ssc = createSelfSignedCert();
        Supplier<SSLEngine> supplier = CaptureProxy.loadSslEngineFromPem(
                    ssc.certificate().getAbsolutePath(),
                    ssc.privateKey().getAbsolutePath(),
                    "",
                    false
                );
        Assertions.assertNotNull(supplier);
        SSLEngine engine = supplier.get();
        Assertions.assertNotNull(engine);
        ssc.delete();
    }

    @Test
    public void testBuildSslEngineSupplierWithPemParams() throws Exception {
        var ssc = createSelfSignedCert();
        var params = CaptureProxy.parseArgs(new String[]{
            "--destinationUri", "http://localhost:9200",
            "--listenPort", "80",
            "--noCapture",
            "--sslCertChainFile", ssc.certificate().getAbsolutePath(),
            "--sslKeyFile", ssc.privateKey().getAbsolutePath()
        });
        Supplier<SSLEngine> supplier = CaptureProxy.buildSslEngineSupplier(params);
        Assertions.assertNotNull(supplier);
        Assertions.assertNotNull(supplier.get());
        ssc.delete();
    }

    @Test
    public void testBuildSslEngineSupplierReturnsNullWhenNoSslConfigured() throws Exception {
        var params = CaptureProxy.parseArgs(new String[]{
            "--destinationUri", "http://localhost:9200",
            "--listenPort", "80",
            "--noCapture"
        });
        Supplier<SSLEngine> supplier = CaptureProxy.buildSslEngineSupplier(params);
        Assertions.assertNull(supplier);
    }

    @Test
    public void testBuildSslEngineSupplierRejectsCertWithoutKey() throws Exception {
        var ssc = createSelfSignedCert();
        var params = CaptureProxy.parseArgs(new String[]{
            "--destinationUri", "http://localhost:9200",
            "--listenPort", "80",
            "--noCapture",
            "--sslCertChainFile", ssc.certificate().getAbsolutePath()
        });
        Assertions.assertThrows(ParameterException.class, () ->
            CaptureProxy.buildSslEngineSupplier(params)
        );
        ssc.delete();
    }

    @Test
    public void testPemSslEngineProducesMultipleEngines() throws Exception {
        var ssc = createSelfSignedCert();
        Supplier<SSLEngine> supplier = CaptureProxy.loadSslEngineFromPem(
                    ssc.certificate().getAbsolutePath(),
                    ssc.privateKey().getAbsolutePath(),
                    null,
                    false
                );
        SSLEngine engine1 = supplier.get();
        SSLEngine engine2 = supplier.get();
        Assertions.assertNotNull(engine1);
        Assertions.assertNotNull(engine2);
        Assertions.assertNotSame(engine1, engine2, "Each call should produce a new SSLEngine instance");
        ssc.delete();
    }
}
