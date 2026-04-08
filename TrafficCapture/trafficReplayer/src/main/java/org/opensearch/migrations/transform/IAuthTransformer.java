package org.opensearch.migrations.transform;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.replay.datahandlers.http.HttpJsonRequestWithFaultingPayload;

public interface IAuthTransformer {
    enum ContextForAuthHeader {
        HEADERS,
        HEADERS_AND_CONTENT_PAYLOAD
    }

    ContextForAuthHeader transformType();

    /**
     * Immutable, reentrant producer of auth signature headers.
     * Each call to {@link #signHeaders} produces fresh headers (e.g., with a new timestamp).
     */
    interface SignatureProducer {
        Map<String, List<String>> signHeaders(HttpJsonRequestWithFaultingPayload msg);
    }

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

        /** Called once per body chunk during pipeline processing. */
        public abstract void consumeNextPayloadPart(ByteBuffer contentChunk);

        /**
         * Finalizes the content hash from accumulated payload parts and returns an
         * immutable, reentrant {@link SignatureProducer}. Must be called exactly once,
         * after all {@link #consumeNextPayloadPart} calls.
         */
        public abstract SignatureProducer finalizeContentHash();
    }
}
