package org.opensearch.migrations.transform;

import java.io.IOException;
import java.nio.file.Path;

/**
 * SPI interface for resolving remote URIs to local files.
 * Implementations are discovered via {@link java.util.ServiceLoader}.
 *
 * <p>For example, an S3 implementation would handle "s3://" URIs by downloading
 * the file to a local temp path.
 */
public interface RemoteConfigSource {
    /** Returns true if this source can handle the given URI (e.g. starts with "s3://"). */
    boolean canHandle(String uri);

    /** Downloads the remote URI to a local temp file and returns the path. */
    Path download(String uri, String suffix) throws IOException;
}
