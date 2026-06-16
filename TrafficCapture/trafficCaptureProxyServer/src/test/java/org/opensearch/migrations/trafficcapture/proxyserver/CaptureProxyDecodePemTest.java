package org.opensearch.migrations.trafficcapture.proxyserver;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class CaptureProxyDecodePemTest {

    private static final String RAW_PEM = "-----BEGIN CERTIFICATE-----\n"
        + "MIIBkTCB+wIJALRiMLAhLaKSMA0GCSqGSIb3DQEBCwUA\n"
        + "-----END CERTIFICATE-----\n";

    @Test
    public void testRawPemPassedThrough() {
        String result = CaptureProxy.decodePemIfBase64(RAW_PEM);
        Assertions.assertEquals(RAW_PEM, result);
    }

    @Test
    public void testBase64EncodedPemDecoded() {
        String encoded = Base64.getEncoder().encodeToString(RAW_PEM.getBytes(StandardCharsets.UTF_8));
        String result = CaptureProxy.decodePemIfBase64(encoded);
        Assertions.assertEquals(RAW_PEM, result);
    }

    @Test
    public void testInvalidBase64ReturnedAsIs() {
        String notBase64 = "not-valid-base64-!!!@@@";
        String result = CaptureProxy.decodePemIfBase64(notBase64);
        Assertions.assertEquals(notBase64, result);
    }
}
