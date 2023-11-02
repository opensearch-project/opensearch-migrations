package org.opensearch.migrations.transform;

import org.opensearch.migrations.replay.datahandlers.http.HttpJsonMessageWithFaultingPayload;
import org.opensearch.migrations.replay.datahandlers.http.IHttpMessage;

import java.io.IOException;

public interface IAuthTransformerFactory extends AutoCloseable {
    IAuthTransformer getAuthTransformer(IHttpMessage httpMessage);
    default void close() throws IOException {}

    class NullAuthTransformerFactory implements IAuthTransformerFactory {
        public final static NullAuthTransformerFactory instance = new NullAuthTransformerFactory();

        public NullAuthTransformerFactory() {}

        @Override
        public IAuthTransformer getAuthTransformer(IHttpMessage httpMessage) {
            return null;
        }
    }
}
