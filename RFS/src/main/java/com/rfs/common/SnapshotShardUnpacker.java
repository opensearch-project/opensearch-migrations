package com.rfs.common;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.store.NativeFSLockFactory;
import org.apache.lucene.util.BytesRef;


public class SnapshotShardUnpacker {
    private static final Logger logger = LogManager.getLogger(SnapshotShardUnpacker.class);

    public static void unpack(SourceRepo repo, ShardMetadata.Data shardMetadata, Path luceneFilesBasePath, int bufferSize) throws Exception {
        // Some constants
        NativeFSLockFactory lockFactory = NativeFSLockFactory.INSTANCE;

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
                try (InputStream stream = new PartSliceStream(repo, fileMetadata, shardMetadata.getIndexId(), shardMetadata.getShardId())) {
                    final byte[] buffer = new byte[Math.toIntExact(Math.min(bufferSize, fileMetadata.getLength()))];
                    int length;
                    while ((length = stream.read(buffer)) > 0) {
                        indexOutput.writeBytes(buffer, 0, length);
                    }
                }
            }
            indexOutput.close();
        }        
    }
    
}
