package org.opensearch.migrations.transform;

public class StaticAuthTransformerFactory implements IAuthTransformerFactory {
    private final String authHeaderValue;

    public StaticAuthTransformerFactory(String authHeaderValue) {
        this.authHeaderValue = authHeaderValue;
    }

    @Override
    public IAuthTransformer getAuthTransformer(IHttpMessage httpMessage) {
        return new IAuthTransformer.HeadersOnlyTransformer() {
            @Override
            public void rewriteHeaders(HttpJsonMessageWithFaultingPayload msg) {
                msg.headers().put("authorization", authHeaderValue);
                // TODO - wipe out more headers too?
            }
        };
    }
}
