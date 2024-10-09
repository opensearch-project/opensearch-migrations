package org.opensearch.migrations.transform;

import org.opensearch.migrations.replay.datahandlers.http.HttpJsonRequestWithFaultingPayload;

public class StaticAuthTransformerFactory implements IAuthTransformerFactory {
    private final String authHeaderValue;

    public StaticAuthTransformerFactory(String authHeaderValue) {
        this.authHeaderValue = authHeaderValue;
    }

    @Override
    public IAuthTransformer getAuthTransformer(HttpJsonRequestWithFaultingPayload httpMessage) {
        return new IAuthTransformer.HeadersOnlyTransformer() {
            @Override
            public void rewriteHeaders(HttpJsonRequestWithFaultingPayload msg) {
                msg.headers().put("authorization", authHeaderValue);
            }
        };
    }
}
