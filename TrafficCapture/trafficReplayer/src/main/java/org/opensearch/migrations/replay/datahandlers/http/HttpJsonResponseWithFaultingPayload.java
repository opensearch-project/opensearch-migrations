package org.opensearch.migrations.replay.datahandlers.http;

import org.opensearch.migrations.transform.JsonKeysForHttpMessage;

public class HttpJsonResponseWithFaultingPayload extends HttpJsonMessageWithFaultingPayload {
    public String code() {
        return (String) this.get(JsonKeysForHttpMessage.STATUS_CODE_KEY);
    }

    public void setCode(String value) {
        this.put(JsonKeysForHttpMessage.STATUS_CODE_KEY, value);
    }

    public String reason() {
        return (String) this.get(JsonKeysForHttpMessage.STATUS_REASON_KEY);
    }

    public void setReason(String reason) {
        this.put(JsonKeysForHttpMessage.STATUS_REASON_KEY, reason);
    }
}
