package org.opensearch.migrations.transform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ServiceLoader;

import lombok.extern.slf4j.Slf4j;

/**
 * Resolves a URI string to a local {@link Path}. If the URI matches a registered
 * {@link RemoteConfigSource} (discovered via SPI), the file is downloaded first.
 * Otherwise the URI is treated as a local file path.
 */
@Slf4j
public final class ConfigUriResolver {
    private ConfigUriResolver() {}

    /**
     * Resolve a URI to a local path. Remote URIs (e.g. s3://) are downloaded
     * to a temp file via the matching {@link RemoteConfigSource} SPI implementation.
     *
     * @param uri    local path or remote URI
     * @param suffix file suffix hint for temp files (e.g. ".py", ".tar.gz")
     * @return local path to the resolved file
     */
    public static Path resolve(String uri, String suffix) throws IOException {
        for (var source : ServiceLoader.load(RemoteConfigSource.class)) {
            if (source.canHandle(uri)) {
                log.atInfo().setMessage("Resolving {} via {}").addArgument(uri)
                    .addArgument(source.getClass().getSimpleName()).log();
                return source.download(uri, suffix);
            }
        }
        return Path.of(uri);
    }
}
