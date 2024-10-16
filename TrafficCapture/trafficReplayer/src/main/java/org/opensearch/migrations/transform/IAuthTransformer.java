package org.opensearch.migrations.transform;

import java.nio.ByteBuffer;

import org.opensearch.migrations.replay.datahandlers.http.HttpJsonRequestWithFaultingPayload;

public interface IAuthTransformer {
    enum ContextForAuthHeader {
        HEADERS,
        HEADERS_AND_CONTENT_PAYLOAD
    }

    ContextForAuthHeader transformType();

    abstract class HeadersOnlyTransformer implements IAuthTransformer {
        @Override
        public ContextForAuthHeader transformType() {
            return ContextForAuthHeader.HEADERS;
        }

        public abstract void rewriteHeaders(HttpJsonRequestWithFaultingPayload msg);
    }

    abstract class StreamingFullMessageTransformer implements IAuthTransformer {
        @Override
        public ContextForAuthHeader transformType() {
            return ContextForAuthHeader.HEADERS_AND_CONTENT_PAYLOAD;
        }

        public abstract void consumeNextPayloadPart(ByteBuffer contentChunk);

        public abstract void finalizeSignature(HttpJsonRequestWithFaultingPayload msg);
    }
}
