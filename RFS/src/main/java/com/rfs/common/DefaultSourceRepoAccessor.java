package com.rfs.common;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/*
 * Provides "simple" access to the underlying files in the source repo without any special behavior
 */
public class DefaultSourceRepoAccessor extends SourceRepoAccessor {
    public DefaultSourceRepoAccessor(SourceRepo repo) {
        super(repo);
    }

    @Override
    protected InputStream load(Path path) {
        try {
            return Files.newInputStream(path);
        } catch (Exception e) {
            throw new CouldNotLoadRepoFile("Could not load file: " + path, e);
        }
    }
}
