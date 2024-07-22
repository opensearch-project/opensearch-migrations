package org.opensearch.migrations.transform;

import org.opensearch.migrations.IHttpMessage;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonMessageWithFaultingPayload;

public class RemovingAuthTransformerFactory implements IAuthTransformerFactory {

    public static final RemovingAuthTransformerFactory instance = new RemovingAuthTransformerFactory();

    private RemovingAuthTransformerFactory() {}

    @Override
    public IAuthTransformer getAuthTransformer(IHttpMessage httpMessage) {
        return RemovingAuthTransformer.instance;
    }

    private static class RemovingAuthTransformer extends IAuthTransformer.HeadersOnlyTransformer {
        private static final RemovingAuthTransformer instance = new RemovingAuthTransformer();

        @Override
        public void rewriteHeaders(HttpJsonMessageWithFaultingPayload msg) {
            msg.headersInternal().remove("authorization");
        }
    }
}
