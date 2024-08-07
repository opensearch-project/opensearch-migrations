package org.opensearch.migrations.transform;

import java.io.IOException;

import org.opensearch.migrations.replay.datahandlers.http.HttpJsonMessageWithFaultingPayload;

public interface IAuthTransformerFactory extends AutoCloseable {
    IAuthTransformer getAuthTransformer(HttpJsonMessageWithFaultingPayload httpMessage);

    default void close() throws IOException {}

    class NullAuthTransformerFactory implements IAuthTransformerFactory {
        public static final NullAuthTransformerFactory instance = new NullAuthTransformerFactory();

        @Override
        public IAuthTransformer getAuthTransformer(HttpJsonMessageWithFaultingPayload httpMessage) {
            return null;
        }
    }
}
