package org.opensearch.migrations.transform;

import org.opensearch.migrations.replay.datahandlers.http.HttpJsonMessageWithFaultingPayload;
import org.opensearch.migrations.replay.datahandlers.http.IHttpMessage;

import java.nio.ByteBuffer;

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
        public abstract void rewriteHeaders(HttpJsonMessageWithFaultingPayload msg);
    }

    abstract class StreamingFullMessageTransformer implements IAuthTransformer {
        @Override
        public ContextForAuthHeader transformType() {
            return ContextForAuthHeader.HEADERS_AND_CONTENT_PAYLOAD; }
        public abstract void consumeNextPayloadPart(ByteBuffer contentChunk);
        public abstract void finalize(HttpJsonMessageWithFaultingPayload msg);
    }
}
