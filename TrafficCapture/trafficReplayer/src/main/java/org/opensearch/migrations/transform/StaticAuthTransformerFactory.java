package org.opensearch.migrations.transform;

import org.opensearch.migrations.replay.datahandlers.http.HttpJsonMessageWithFaultingPayload;

public class StaticAuthTransformerFactory implements IAuthTransformerFactory {
    private final String authHeaderValue;

    public StaticAuthTransformerFactory(String authHeaderValue) {
        this.authHeaderValue = authHeaderValue;
    }

    @Override
    public IAuthTransformer getAuthTransformer(HttpJsonMessageWithFaultingPayload httpMessage) {
        return new IAuthTransformer.HeadersOnlyTransformer() {
            @Override
            public void rewriteHeaders(HttpJsonMessageWithFaultingPayload msg) {
                msg.headers().put("authorization", authHeaderValue);
            }
        };
    }
}
