package org.opensearch.migrations.replay;

import io.netty.buffer.ByteBuf;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.opensearch.migrations.replay.datahandlers.http.IHttpMessageMetadata;

public class SigV4Signer {
    public IHttpMessageMetadata messageMetadata;
    public String processedPayload;
    public Map<String, String> signedHeaders;
    public SigV4Signer(IHttpMessageMetadata messageMetadata){ // add defaultcredentials.
        this.messageMetadata = messageMetadata;
        this.signedHeaders = new HashMap<>();
    }

    public void processNextPayload(ByteBuf payloadChunk) {

        this.processedPayload = payloadChunk.toString(StandardCharsets.UTF_8).toUpperCase();
    }

    public Map<String, String> getSignatureheaders() {
        // Simple placeholder logic.
        // This should return the signed header.
        if (this.processedPayload != null) {
            this.signedHeaders.put("Signature", this.processedPayload);
        }

        return this.signedHeaders;
    }
}
