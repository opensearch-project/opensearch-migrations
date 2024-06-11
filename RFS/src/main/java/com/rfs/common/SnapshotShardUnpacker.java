package com.rfs.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;


import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.store.NativeFSLockFactory;
import org.apache.lucene.util.BytesRef;

@RequiredArgsConstructor
public class SnapshotShardUnpacker {
    private static final Logger logger = LogManager.getLogger(SnapshotShardUnpacker.class);
    protected final SourceRepoAccessor repoAccessor;
    protected final  Path luceneFilesBasePath;
    protected final int bufferSize;

    public void unpack(ShardMetadata.Data shardMetadata) {
        try {
            // Some constants
            NativeFSLockFactory lockFactory = NativeFSLockFactory.INSTANCE;

            // Ensure the blob files are prepped, if they need to be
            repoAccessor.prepBlobFiles(shardMetadata);

            // Create the directory for the shard's lucene files
            Path luceneIndexDir = Paths.get(luceneFilesBasePath + "/" + shardMetadata.getIndexName() + "/" + shardMetadata.getShardId());
            Files.createDirectories(luceneIndexDir);
            final FSDirectory primaryDirectory = FSDirectory.open(luceneIndexDir, lockFactory);

            for (ShardMetadata.FileInfo fileMetadata : shardMetadata.getFiles()) {
                logger.info("Unpacking - Blob Name: " + fileMetadata.getName() + ", Lucene Name: " + fileMetadata.getPhysicalName());
                IndexOutput indexOutput = primaryDirectory.createOutput(fileMetadata.getPhysicalName(), IOContext.DEFAULT);

                if (fileMetadata.getName().startsWith("v__")) {
                    final BytesRef hash = fileMetadata.getMetaHash();
                    indexOutput.writeBytes(hash.bytes, hash.offset, hash.length);
                } else {
                    try (InputStream stream = new PartSliceStream(repoAccessor, fileMetadata, shardMetadata.getIndexId(), shardMetadata.getShardId())) {
                        final byte[] buffer = new byte[Math.toIntExact(Math.min(bufferSize, fileMetadata.getLength()))];
                        int length;
                        while ((length = stream.read(buffer)) > 0) {
                            indexOutput.writeBytes(buffer, 0, length);
                        }
                    }
                }
                indexOutput.close();
            }
        } catch (Exception e) {
            throw new CouldNotUnpackShard("Could not unpack shard: Index " + shardMetadata.getIndexId() + ", Shard " + shardMetadata.getShardId(), e);
        }
    }

    public void cleanUp(ShardMetadata.Data shardMetadata) {
        try {
            Path luceneIndexDir = Paths.get(luceneFilesBasePath + "/" + shardMetadata.getIndexName() + "/" + shardMetadata.getShardId());
            if (Files.exists(luceneIndexDir)) {
                deleteRecursively(luceneIndexDir);
            }
            
        } catch (Exception e) {
            throw new CouldNotCleanUpShard("Could not clean up shard: Index " + shardMetadata.getIndexId() + ", Shard " + shardMetadata.getShardId(), e);
        }
    }

    protected void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
                for (Path entry : entries) {
                    deleteRecursively(entry);
                }
            }
        }
        Files.delete(path);
    }

    public static class CouldNotCleanUpShard extends RfsException {
        public CouldNotCleanUpShard(String message, Exception e) {
            super(message, e);
        }
    }

    public static class CouldNotUnpackShard extends RfsException {
        public CouldNotUnpackShard(String message, Exception e) {
            super(message, e);
        }
    }
    
}
