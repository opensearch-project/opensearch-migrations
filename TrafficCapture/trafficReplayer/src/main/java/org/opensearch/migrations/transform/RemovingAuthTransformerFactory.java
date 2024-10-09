package org.opensearch.migrations.transform;

import org.opensearch.migrations.replay.datahandlers.http.HttpJsonRequestWithFaultingPayload;

public class RemovingAuthTransformerFactory implements IAuthTransformerFactory {

    public static final RemovingAuthTransformerFactory instance = new RemovingAuthTransformerFactory();

    private RemovingAuthTransformerFactory() {}

    @Override
    public IAuthTransformer getAuthTransformer(HttpJsonRequestWithFaultingPayload httpMessage) {
        return RemovingAuthTransformer.instance;
    }

    private static class RemovingAuthTransformer extends IAuthTransformer.HeadersOnlyTransformer {
        private static final RemovingAuthTransformer instance = new RemovingAuthTransformer();

        @Override
        public void rewriteHeaders(HttpJsonRequestWithFaultingPayload msg) {
            msg.headers().remove("authorization");
        }
    }
}
