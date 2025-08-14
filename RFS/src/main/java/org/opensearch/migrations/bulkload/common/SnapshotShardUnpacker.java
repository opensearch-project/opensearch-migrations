package org.opensearch.migrations.bulkload.common;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.models.ShardFileInfo;
import org.opensearch.migrations.bulkload.models.ShardMetadata;

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
    private final Path luceneFilesBasePath;
    private final ShardMetadata baseShardMetadata;
    private final ShardMetadata shardMetadata;
    private final int bufferSize;

    @RequiredArgsConstructor
    public static class Factory {
        private final SourceRepoAccessor repoAccessor;
        private final Path luceneFilesBasePath;
        private final int bufferSize;

        public SnapshotShardUnpacker create(ShardMetadata shardMetadata) {
            return new SnapshotShardUnpacker(repoAccessor, luceneFilesBasePath, null, shardMetadata, bufferSize);
        }

        public SnapshotShardUnpacker create(ShardMetadata baseShardMetadata, ShardMetadata shardMetadata) {
            return new SnapshotShardUnpacker(repoAccessor, luceneFilesBasePath, baseShardMetadata, shardMetadata, bufferSize);
        }
    }

    public Path unpack() {
        if (baseShardMetadata == null) {
            return standardUnpack();
        }
        return deltaUnpack();
    }

    public Path standardUnpack() {
        try {
            // Some constants
            NativeFSLockFactory lockFactory = NativeFSLockFactory.INSTANCE;

            // Ensure the blob files are prepped, if they need to be
            repoAccessor.prepBlobFiles(shardMetadata);

            // Create the directory for the shard's lucene files
            Path luceneIndexDir = Paths.get(
                luceneFilesBasePath + "/" + shardMetadata.getIndexName() + "/" + shardMetadata.getShardId()
            );
            Files.createDirectories(luceneIndexDir);
            try (FSDirectory primaryDirectory = FSDirectory.open(luceneIndexDir, lockFactory)) {
                for (ShardFileInfo fileMetadata : shardMetadata.getFiles()) {
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
                                    shardMetadata.getIndexId(),
                                    shardMetadata.getShardId()
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
            return luceneIndexDir;
        } catch (Exception e) {
            throw new CouldNotUnpackShard(
                "Could not unpack shard: Index " + shardMetadata.getIndexId() + ", Shard " + shardMetadata.getShardId(),
                e
            );
        }
    }

    public Path deltaUnpack() {
        try {
            // Some constants
            NativeFSLockFactory lockFactory = NativeFSLockFactory.INSTANCE;

            // Use prepBlobFiles on new shard
            repoAccessor.prepBlobFiles(shardMetadata);

            // Create the directory for the shard's lucene files
            Path luceneIndexDir = Paths.get(
                luceneFilesBasePath + "/" + shardMetadata.getIndexName() + "/" + shardMetadata.getShardId()
            );
            Files.createDirectories(luceneIndexDir);

            Set<ShardFileInfo> combinedFiles =
                Stream.concat(shardMetadata.getFiles().stream(),
                        baseShardMetadata.getFiles().stream())
                    .collect(Collectors.toCollection(
                        () -> new TreeSet<>(Comparator.comparing(ShardFileInfo::key))));

            try (FSDirectory primaryDirectory = FSDirectory.open(luceneIndexDir, lockFactory)) {
                for (ShardFileInfo fileMetadata : combinedFiles) {
                    log.atInfo().setMessage("Delta Unpacking - Blob Name: {}, Lucene Name: {}")
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
                                    shardMetadata.getIndexId(),
                                    shardMetadata.getShardId()
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
            return luceneIndexDir;
        } catch (Exception e) {
            throw new CouldNotUnpackShard(
                "Could not delta unpack shard: Index " + shardMetadata.getIndexId() + ", Shard " + shardMetadata.getShardId()
                + " BaseSnapshot " + baseShardMetadata.getSnapshotName() + " CurrentSnapshot " + shardMetadata.getSnapshotName(),
                e
            );
        }
    }


    public static class CouldNotUnpackShard extends RfsException {
        public CouldNotUnpackShard(String message, Exception e) {
            super(message, e);
        }
    }

}
