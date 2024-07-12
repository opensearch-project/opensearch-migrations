package com.rfs.common;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/*
 * Provides access to the underlying files in the source repo and deletes the files after the Stream is closed.  This
 * is useful/interesting in the case where the files are large/numerous and you can easily re-acquire them - such as
 * if they are being loaded from S3.
 * 
 * TODO: find a better approach to this (see https://opensearch.atlassian.net/browse/MIGRATIONS-1786)
 */
public class EphemeralSourceRepoAccessor extends SourceRepoAccessor {
    private static final Logger logger = LogManager.getLogger(EphemeralSourceRepoAccessor.class);

    public EphemeralSourceRepoAccessor(SourceRepo repo) {
        super(repo);
    }

    @Override
    protected InputStream load(Path path) {
        try {
            return new EphemeralFileInputStream(path);
        } catch (Exception e) {
            throw new CouldNotLoadRepoFile("Could not load file: " + path, e);
        }
    }

    public static class EphemeralFileInputStream extends FileInputStream {
        private final Path filePath;

        public EphemeralFileInputStream(Path filePath) throws IOException {
            super(filePath.toFile());
            this.filePath = filePath;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                logger.info("Deleting local file: {}", filePath);
                logger.warn("See: https://opensearch.atlassian.net/browse/MIGRATIONS-1786");
                Files.deleteIfExists(filePath);
            }
        }
    }
}
