/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.knn.index.codec.KNN80Codec;

import java.io.IOException;
import java.util.Set;

import org.apache.lucene.codecs.CompoundDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;

/**
 * Minimal CompoundDirectory that handles KNN engine files.
 * KNN files (.hnsw, .nmslib, .faiss) are stored outside compound file.
 */
@SuppressWarnings("java:S120") // Package name must match OpenSearch KNN plugin for Lucene codec SPI
public class KNN80CompoundDirectory extends CompoundDirectory {
    private static final Set<String> KNN_EXTENSIONS = Set.of("hnsw", "nmslib", "faiss");
    
    private final CompoundDirectory delegate;
    private final Directory dir;

    public KNN80CompoundDirectory(CompoundDirectory delegate, Directory dir) {
        this.delegate = delegate;
        this.dir = dir;
    }

    @Override
    public void checkIntegrity() throws IOException {
        delegate.checkIntegrity();
    }

    @Override
    public String[] listAll() throws IOException {
        return delegate.listAll();
    }

    @Override
    public long fileLength(String name) throws IOException {
        return delegate.fileLength(name);
    }

    @Override
    public IndexInput openInput(String name, IOContext context) throws IOException {
        // KNN engine files are stored outside compound file
        if (isKnnFile(name)) {
            return dir.openInput(name, context);
        }
        return delegate.openInput(name, context);
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    @Override
    public Set<String> getPendingDeletions() throws IOException {
        return delegate.getPendingDeletions();
    }

    private static boolean isKnnFile(String name) {
        int dot = name.lastIndexOf('.');
        if (dot == -1) return false;
        String ext = name.substring(dot + 1);
        return KNN_EXTENSIONS.contains(ext);
    }
}
