package org.opensearch.migrations.transform;

import org.opensearch.migrations.replay.datahandlers.http.HttpJsonMessageWithFaultingPayload;
import org.opensearch.migrations.replay.datahandlers.http.IHttpMessage;

public interface IAuthTransformerFactory {
    IAuthTransformer getAuthTransformer(IHttpMessage httpMessage);

    class NullAuthTransformerFactory implements IAuthTransformerFactory {
        public final static NullAuthTransformerFactory instance = new NullAuthTransformerFactory();

        public NullAuthTransformerFactory() {}

        @Override
        public IAuthTransformer getAuthTransformer(IHttpMessage httpMessage) {
            return null;
        }
    }
}
