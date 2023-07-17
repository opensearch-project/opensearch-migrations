package org.opensearch.migrations.replay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonMessageWithFaultingPayload;

import java.nio.charset.Charset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SigV4SignerTest {

    private SigV4Signer sigV4Signer;
    private HttpJsonMessageWithFaultingPayload testMetadata;

    @BeforeEach
    void setUp() {
        testMetadata = new HttpJsonMessageWithFaultingPayload();
        sigV4Signer = new SigV4Signer(testMetadata);
    }

    @Test
    void processNextPayload() {
        ByteBuf payload = Unpooled.copiedBuffer("Original payload", Charset.defaultCharset());
        sigV4Signer.processNextPayload(payload);

        // Testing if expected is uppercase only because that's what the current state of signing looks like.
        String expectedPayload = "ORIGINAL PAYLOAD";

        Map<String, String> signatureHeaders = sigV4Signer.getSignatureheaders();
        assertEquals(expectedPayload, signatureHeaders.get("Signature"));
    }

    @Test
    void getSignatureHeaders() {
        String processedPayload = "PROCESSED PAYLOAD";
        // Setting payload in headers
        sigV4Signer.getSignatureheaders().put("Signature", processedPayload);

        Map<String, String> result = sigV4Signer.getSignatureheaders();
        // Were headers succesfully retrieved?
        assertEquals(Map.of("Signature", processedPayload), result);
    }
}
