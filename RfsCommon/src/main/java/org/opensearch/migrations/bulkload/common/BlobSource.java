package org.opensearch.migrations.bulkload.common;

import java.io.InputStream;
import java.nio.file.Path;

/**
 * Abstraction for reading blob data from a storage backend.
 * Implementations provide access to local filesystem, S3, or other storage.
 */
@FunctionalInterface
public interface BlobSource {
    /**
     * Reads the blob at the given path and returns its contents as an InputStream.
     *
     * @param path the path to the blob
     * @return an InputStream for reading the blob contents
     * @throws SourceRepoAccessor.CouldNotLoadRepoFile if the blob cannot be read
     */
    InputStream readBlob(Path path);

    /**
     * Creates a BlobSource that reads from the local filesystem.
     */
    static BlobSource fromLocalFilesystem() {
        return path -> {
            try {
                return java.nio.file.Files.newInputStream(path);
            } catch (Exception e) {
                throw new SourceRepoAccessor.CouldNotLoadRepoFile("Could not load file: " + path, e);
            }
        };
    }
}
