package org.opensearch.migrations.bulkload.common;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.opensearch.migrations.bulkload.models.ShardFileInfo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import shadow.lucene9.org.apache.lucene.store.FSDirectory;
import shadow.lucene9.org.apache.lucene.store.IOContext;
import shadow.lucene9.org.apache.lucene.store.IndexOutput;
import shadow.lucene9.org.apache.lucene.store.NativeFSLockFactory;
import shadow.lucene9.org.apache.lucene.util.BytesRef;

@RequiredArgsConstructor
@Slf4j
public class SnapshotShardUnpacker {
    private final SourceRepoAccessor repoAccessor;
    private final Set<ShardFileInfo> filesToUnpack;
    private final Path targetDirectory;
    private final String indexId;
    private final int shardId;
    private final int bufferSize;

    @RequiredArgsConstructor
    public static class Factory {
        private final SourceRepoAccessor repoAccessor;
        private final Path luceneFilesBasePath;
        private final int bufferSize;

        public SourceRepoAccessor getRepoAccessor() {
            return repoAccessor;
        }

        public SnapshotShardUnpacker create(
            Set<ShardFileInfo> filesToUnpack,
            String indexName,
            String indexId,
            int shardId
        ) {
            Path targetDirectory = luceneFilesBasePath.resolve(indexName).resolve(String.valueOf(shardId));
            return new SnapshotShardUnpacker(
                repoAccessor,
                filesToUnpack,
                targetDirectory,
                indexId,
                shardId,
                bufferSize
            );
        }
    }

    public Path unpack() {
        try {
            // Some constants
            NativeFSLockFactory lockFactory = NativeFSLockFactory.INSTANCE;

            // Create the directory for the shard's lucene files
            Files.createDirectories(targetDirectory);

            try (FSDirectory primaryDirectory = FSDirectory.open(targetDirectory, lockFactory)) {
                for (ShardFileInfo fileMetadata : filesToUnpack) {
                    log.atInfo().setMessage("Unpacking - Blob Name: {}, Lucene Name: {}")
                        .addArgument(fileMetadata::getName)
                        .addArgument(fileMetadata::getPhysicalName)
                        .log();
                    
                    try (
                        IndexOutput indexOutput = primaryDirectory.createOutput(
                            fileMetadata.getPhysicalName(),
                            IOContext.DEFAULT
                        );
                    ) {
                        if (fileMetadata.getName().startsWith("v__")) {
                            final BytesRef hash = fileMetadata.getMetaHash();
                            indexOutput.writeBytes(hash.bytes, hash.offset, hash.length);
                        } else {
                            try (
                                InputStream stream = new PartSliceStream(
                                    repoAccessor,
                                    fileMetadata,
                                    indexId,
                                    shardId
                                )
                            ) {
                                final byte[] buffer = new byte[Math.toIntExact(
                                    Math.min(bufferSize, fileMetadata.getLength())
                                )];
                                int length;
                                while ((length = stream.read(buffer)) > 0) {
                                    indexOutput.writeBytes(buffer, 0, length);
                                }
                            }
                        }
                    }
                }
            }
            return targetDirectory;
        } catch (Exception e) {
            String errorMessage = "Could not unpack shard: Index " + indexId + ", Shard " + shardId;
            throw new CouldNotUnpackShard(errorMessage, e);
        }
    }

    public static class CouldNotUnpackShard extends RfsException {
        public CouldNotUnpackShard(String message, Exception e) {
            super(message, e);
        }
    }
}
