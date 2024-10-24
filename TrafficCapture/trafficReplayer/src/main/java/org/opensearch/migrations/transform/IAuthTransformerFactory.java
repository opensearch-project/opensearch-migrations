package org.opensearch.migrations.transform;

import java.io.IOException;

import org.opensearch.migrations.replay.datahandlers.http.HttpJsonRequestWithFaultingPayload;

public interface IAuthTransformerFactory extends AutoCloseable {
    IAuthTransformer getAuthTransformer(HttpJsonRequestWithFaultingPayload httpMessage);

    default void close() throws IOException {}

    class NullAuthTransformerFactory implements IAuthTransformerFactory {
        public static final NullAuthTransformerFactory instance = new NullAuthTransformerFactory();

        @Override
        public IAuthTransformer getAuthTransformer(HttpJsonRequestWithFaultingPayload httpMessage) {
            return null;
        }
    }
}
