package org.opensearch.migrations.replay.datahandlers.http;

import java.util.LinkedHashMap;
import java.util.Map;

import org.opensearch.migrations.replay.datahandlers.PayloadAccessFaultingMap;
import org.opensearch.migrations.transform.JsonKeysForHttpMessage;

public class HttpJsonMessageWithFaultingPayload extends LinkedHashMap<String, Object> {

    public static final int MESSAGE_SCHEMA_VERSION = 2;

    public HttpJsonMessageWithFaultingPayload() {}

    public HttpJsonMessageWithFaultingPayload(Map<String, ?> m) {
        super(m);
        put(JsonKeysForHttpMessage.HTTP_MESSAGE_SCHEMA_VERSION_KEY, MESSAGE_SCHEMA_VERSION);
    }

    public String protocol() {
        return (String) this.get(JsonKeysForHttpMessage.PROTOCOL_KEY);
    }

    public void setProtocol(String value) {
        this.put(JsonKeysForHttpMessage.PROTOCOL_KEY, value);
    }

    public ListKeyAdaptingCaseInsensitiveHeadersMap headers() {
        return (ListKeyAdaptingCaseInsensitiveHeadersMap) headersUnsafe();
    }

    protected Object headersUnsafe() {
        return this.get(JsonKeysForHttpMessage.HEADERS_KEY);
    }

    public void setHeaders(ListKeyAdaptingCaseInsensitiveHeadersMap value) {
        this.put(JsonKeysForHttpMessage.HEADERS_KEY, value);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> payload() {
        return (Map<String, Object>) this.get(JsonKeysForHttpMessage.PAYLOAD_KEY);
    }

    public void setPayloadFaultMap(PayloadAccessFaultingMap value) {
        this.put(JsonKeysForHttpMessage.PAYLOAD_KEY, value);
    }
}
