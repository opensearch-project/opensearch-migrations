package com.rfs.common;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;


/*
 * Provides access to the underlying files in the source repo, but deletes the files after they are read.  This
 * is useful/interesting in the case where the files are large/numerous and you can easily re-acquire them - such as
 * if they are being loaded from S3.
 */
public class DeletingSourceRepoAccessor extends SourceRepoAccessor {
    public DeletingSourceRepoAccessor(SourceRepo repo) {
        super(repo);
    }

    @Override
    protected InputStream load(Path path) {
        try {
            return new DeletingFileInputStream(path);
        } catch (Exception e) {
            throw new CouldNotLoadRepoFile("Could not load file: " + path, e);
        }
    }

    public static class DeletingFileInputStream extends FileInputStream {
        private final Path filePath;

        public DeletingFileInputStream(Path filePath) throws IOException {
            super(filePath.toFile());
            this.filePath = filePath;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                Files.deleteIfExists(filePath);
            }
        }
    }

    public static class CouldNotLoadRepoFile extends RuntimeException {
        public CouldNotLoadRepoFile(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
