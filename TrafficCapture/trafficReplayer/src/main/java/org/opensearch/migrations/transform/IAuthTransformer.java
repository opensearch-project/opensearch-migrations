package org.opensearch.migrations.transform;

import org.opensearch.migrations.replay.datahandlers.http.HttpJsonMessageWithFaultingPayload;
import org.opensearch.migrations.replay.datahandlers.http.IHttpMessage;

import java.nio.ByteBuffer;
import java.util.function.Consumer;
import java.util.function.Function;

public interface IAuthTransformer {
    public enum ContextForAuthHeader {
        HEADERS,
        HEADERS_AND_CONTENT_PAYLOAD
    }

    ContextForAuthHeader transformType(IHttpMessage httpMessage);


    abstract class HeadersOnlyTransformer implements IAuthTransformer {
        public ContextForAuthHeader transformType(IHttpMessage httpMessage) {
            return ContextForAuthHeader.HEADERS; }
        public abstract void rewriteHeaders(HttpJsonMessageWithFaultingPayload msg);
    }

    abstract class StreamingFullMessageTransformer implements IAuthTransformer {
        public ContextForAuthHeader transformType(IHttpMessage httpMessage) {
            return ContextForAuthHeader.HEADERS_AND_CONTENT_PAYLOAD; }
        abstract void consumeNextChunk(ByteBuffer contentChunk);
        abstract void finalize(HttpJsonMessageWithFaultingPayload msg);
    }
}
