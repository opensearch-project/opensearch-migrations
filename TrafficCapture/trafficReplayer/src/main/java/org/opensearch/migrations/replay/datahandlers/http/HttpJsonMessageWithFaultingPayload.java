package org.opensearch.migrations.replay.datahandlers.http;

import org.opensearch.migrations.replay.datahandlers.PayloadAccessFaultingMap;

import java.util.LinkedHashMap;
import java.util.Map;

public class HttpJsonMessageWithFaultingPayload extends LinkedHashMap<String, Object> implements IHttpMessageMetadata {
    public final static String METHOD = "method";
    public final static String URI = "URI";
    public final static String PROTOCOL = "protocol";
    public final static String HEADERS = "headers";
    public final static String PAYLOAD = "payload";

    public HttpJsonMessageWithFaultingPayload() {
    }

    public HttpJsonMessageWithFaultingPayload(Map<? extends String, ?> m) {
        super(m);
    }

    @Override
    public String method() {
        return (String) this.get(METHOD);
    }
    public void setMethod(String value) {
        this.put(METHOD, value);
    }
    @Override
    public String uri() {
        return (String) this.get(URI);
    }
    public void setUri(String value) {
        this.put(URI, value);
    }

    @Override
    public String protocol() {
        return (String) this.get(PROTOCOL);
    }
    public void setProtocol(String value) {
        this.put(PROTOCOL, value);
    }

    @Override
    public ListKeyAdaptingCaseInsensitiveHeadersMap headers() {
        return (ListKeyAdaptingCaseInsensitiveHeadersMap) this.get(HEADERS);
    }
    public void setHeaders(ListKeyAdaptingCaseInsensitiveHeadersMap value) {
        this.put(HEADERS, value);
    }
    public Map<String,Object> payload() {
        return (Map<String,Object>) this.get(PAYLOAD);
    }
    public void setPayloadFaultMap(PayloadAccessFaultingMap value) {
        this.put(PAYLOAD, value);
    }
}

