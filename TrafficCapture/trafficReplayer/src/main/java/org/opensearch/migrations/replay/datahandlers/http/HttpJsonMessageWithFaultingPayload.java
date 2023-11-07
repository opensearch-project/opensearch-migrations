package org.opensearch.migrations.replay.datahandlers.http;

import org.opensearch.migrations.replay.datahandlers.PayloadAccessFaultingMap;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class HttpJsonMessageWithFaultingPayload extends LinkedHashMap<String, Object> implements IHttpMessage {
    public final static String METHOD_KEY = "method";
    public final static String URI_KEY = "URI";
    public final static String PROTOCOL_KEY = "protocol";
    public final static String HEADERS_KEY = "headers";
    public final static String PAYLOAD_KEY = "payload";

    public HttpJsonMessageWithFaultingPayload() {
    }

    public HttpJsonMessageWithFaultingPayload(Map<? extends String, ?> m) {
        super(m);
    }

    @Override
    public String method() {
        return (String) this.get(METHOD_KEY);
    }
    public void setMethod(String value) {
        this.put(METHOD_KEY, value);
    }
    @Override
    public String path() {
        return (String) this.get(URI_KEY);
    }
    public void setPath(String value) {
        this.put(URI_KEY, value);
    }

    @Override
    public String protocol() {
        return (String) this.get(PROTOCOL_KEY);
    }
    public void setProtocol(String value) {
        this.put(PROTOCOL_KEY, value);
    }


    @Override
    public Map<String, Object> headersMap() {
        return Collections.unmodifiableMap(headers());
    }

    public ListKeyAdaptingCaseInsensitiveHeadersMap headers() {
        return (ListKeyAdaptingCaseInsensitiveHeadersMap) this.get(HEADERS_KEY);
    }
    public void setHeaders(ListKeyAdaptingCaseInsensitiveHeadersMap value) {
        this.put(HEADERS_KEY, value);
    }
    public Map<String,Object> payload() {
        return (Map<String,Object>) this.get(PAYLOAD_KEY);
    }
    public void setPayloadFaultMap(PayloadAccessFaultingMap value) {
        this.put(PAYLOAD_KEY, value);
    }
}
