package org.opensearch.migrations.replay.datahandlers.http;

import java.util.List;
import java.util.Map;

import org.opensearch.migrations.transform.JsonKeysForHttpMessage;

public class HttpJsonRequestWithFaultingPayload extends HttpJsonMessageWithFaultingPayload {

    public HttpJsonRequestWithFaultingPayload() {
        super();
    }

    public HttpJsonRequestWithFaultingPayload(Map<String, ?> m) {
        super(m);
        put(JsonKeysForHttpMessage.HTTP_MESSAGE_SCHEMA_VERSION_KEY, MESSAGE_SCHEMA_VERSION);
    }

    public String method() {
        return (String) this.get(JsonKeysForHttpMessage.METHOD_KEY);
    }

    public void setMethod(String value) {
        this.put(JsonKeysForHttpMessage.METHOD_KEY, value);
    }

    public String path() {
        return (String) this.get(JsonKeysForHttpMessage.URI_KEY);
    }

    public void setPath(String value) {
        this.put(JsonKeysForHttpMessage.URI_KEY, value);
    }

    public String protocol() {
        return (String) this.get(JsonKeysForHttpMessage.PROTOCOL_KEY);
    }

    public void setProtocol(String value) {
        this.put(JsonKeysForHttpMessage.PROTOCOL_KEY, value);
    }

    @SuppressWarnings("unchecked")
    public static HttpJsonRequestWithFaultingPayload fromObject(Object object) {
        if (!(object instanceof Map<?,?>)) {
            throw new IllegalArgumentException("Object not map, instead was "
                    + object.getClass().getName());
        }

        final HttpJsonRequestWithFaultingPayload response;
        if (!(object instanceof HttpJsonRequestWithFaultingPayload)) {
            response = new HttpJsonRequestWithFaultingPayload((Map<String,Object>) object);
        } else {
            response = (HttpJsonRequestWithFaultingPayload) object;
        }

        var headers = response.headersUnsafe();

        if (headers instanceof ListKeyAdaptingCaseInsensitiveHeadersMap) {
            // No conversion needed
        } else if (headers instanceof StrictCaseInsensitiveHttpHeadersMap) {
            response.setHeaders(new ListKeyAdaptingCaseInsensitiveHeadersMap(
                (StrictCaseInsensitiveHttpHeadersMap) headers
            ));
        } else if (headers instanceof Map<?, ?>) {
            response.setHeaders(new ListKeyAdaptingCaseInsensitiveHeadersMap(
                StrictCaseInsensitiveHttpHeadersMap.fromMap((Map<String, List<String>>) headers)));
        } else {
            throw
                new IllegalArgumentException("Object headers not map, instead was "
                    + headers.getClass().getName());
        }
        return response;
    }
}
