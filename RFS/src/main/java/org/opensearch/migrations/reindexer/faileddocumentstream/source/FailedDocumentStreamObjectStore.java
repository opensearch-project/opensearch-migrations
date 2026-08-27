package org.opensearch.migrations.reindexer.faileddocumentstream.source;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * The object operations the source and the sealer need, narrow enough for an in-memory test double.
 *
 * <p>Implementations must be safe for concurrent use.
 */
public interface FailedDocumentStreamObjectStore extends AutoCloseable {

    /** Keys under a prefix, lexicographically ordered. */
    List<String> listKeys(String prefix) throws IOException;

    /** Caller closes the stream. */
    InputStream open(String key) throws IOException;

    /** Empty when there is no such object. */
    Optional<byte[]> read(String key) throws IOException;

    /** Returns whether this call created the object. A racing writer must lose, not overwrite. */
    boolean putIfAbsent(String key, byte[] body) throws IOException;

    @Override
    default void close() throws Exception {
    }
}
