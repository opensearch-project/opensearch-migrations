package org.opensearch.migrations.replay.datahandlers.http;

import org.opensearch.migrations.replay.datahandlers.PayloadFaultMap;

import java.util.LinkedHashMap;

public class HttpJsonMessageWithFaultablePayload extends LinkedHashMap<String, Object> {
    public final static String METHOD = "method";
    public final static String URI = "URI";
    public final static String PROTOCOL = "protocol";
    public final static String HEADERS = "headers";
    public final static String PAYLOAD = "payload";

    public String method() {
        return (String) this.get(METHOD);
    }
    public void setMethod(String value) {
        this.put(METHOD, value);
    }
    public String uri() {
        return (String) this.get(URI);
    }
    public void setUri(String value) {
        this.put(URI, value);
    }

    public String protocol() {
        return (String) this.get(PROTOCOL);
    }
    public void setProtocol(String value) {
        this.put(PROTOCOL, value);
    }

    public ListKeyAdaptingCaseInsensitiveHeadersMap headers() {
        return (ListKeyAdaptingCaseInsensitiveHeadersMap) this.get(HEADERS);
    }
    public void setHeaders(ListKeyAdaptingCaseInsensitiveHeadersMap value) {
        this.put(HEADERS, value);
    }
    public PayloadFaultMap payload() {
        return (PayloadFaultMap) this.get(PAYLOAD);
    }
    public void setPayload(PayloadFaultMap value) {
        this.put(PAYLOAD, value);
    }
}
