package com.rfs.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Taken from Elasticsearch 6.8, combining the SlicedInputStream and PartSliceStream classes with our special sauce
 * See: https://github.com/elastic/elasticsearch/blob/6.8/server/src/main/java/org/elasticsearch/index/snapshots/blobstore/SlicedInputStream.java
 * See: https://github.com/elastic/elasticsearch/blob/6.8/server/src/main/java/org/elasticsearch/repositories/blobstore/BlobStoreRepository.java#L1403
 */

public class PartSliceStream extends InputStream {
    private final SourceRepo repo;
    private final ShardMetadata.FileInfo fileMetadata;
    private final String indexId;
    private final int shardId;
    private long slice = 0;
    private InputStream currentStream;
    private boolean initialized = false;

    public PartSliceStream(SourceRepo repo, ShardMetadata.FileInfo fileMetadata, String indexId, int shardId) {
        this.repo = repo;
        this.fileMetadata = fileMetadata;
        this.indexId = indexId;
        this.shardId = shardId;
    }

    protected InputStream openSlice(long slice) throws IOException {
        Path filePath = repo.getBlobFilePath(indexId, shardId, fileMetadata.partName(slice));
        return Files.newInputStream(filePath);
    }

    private InputStream nextStream() throws IOException {
        assert initialized == false || currentStream != null;
        initialized = true;

        if (currentStream != null) {
            currentStream.close();
        }
        if (slice < fileMetadata.getNumberOfParts()) {
            currentStream = openSlice(slice++);
        } else {
            currentStream = null;
        }
        return currentStream;
    }

    private InputStream currentStream() throws IOException {
        if (currentStream == null) {
            return initialized ? null : nextStream();
        }
        return currentStream;
    }

    @Override
    public final int read() throws IOException {
        InputStream stream = currentStream();
        if (stream == null) {
            return -1;
        }
        final int read = stream.read();
        if (read == -1) {
            nextStream();
            return read();
        }
        return read;
    }

    @Override
    public final int read(byte[] buffer, int offset, int length) throws IOException {
        final InputStream stream = currentStream();
        if (stream == null) {
            return -1;
        }
        final int read = stream.read(buffer, offset, length);
        if (read <= 0) {
            nextStream();
            return read(buffer, offset, length);
        }
        return read;
    }

    @Override
    public final void close() throws IOException {
        if (currentStream != null) {
            currentStream.close();
        }
        initialized = true;
        currentStream = null;
    }

    @Override
    public final int available() throws IOException {
        InputStream stream = currentStream();
        return stream == null ? 0 : stream.available();
    }

}
