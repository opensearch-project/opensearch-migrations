package org.opensearch.migrations.transform;

import java.io.IOException;

public interface IAuthTransformerFactory extends AutoCloseable {
    IAuthTransformer getAuthTransformer(IHttpMessage httpMessage);

    default void close() throws IOException {}

    class NullAuthTransformerFactory implements IAuthTransformerFactory {
        public static final NullAuthTransformerFactory instance = new NullAuthTransformerFactory();

        @Override
        public IAuthTransformer getAuthTransformer(IHttpMessage httpMessage) {
            return null;
        }
    }
}
