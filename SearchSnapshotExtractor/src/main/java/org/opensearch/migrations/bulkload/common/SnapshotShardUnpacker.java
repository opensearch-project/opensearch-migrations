package org.opensearch.migrations.bulkload.common;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.opensearch.migrations.bulkload.models.ShardFileInfo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import shadow.lucene9.org.apache.lucene.store.FSDirectory;
import shadow.lucene9.org.apache.lucene.store.IOContext;
import shadow.lucene9.org.apache.lucene.store.IndexOutput;
import shadow.lucene9.org.apache.lucene.store.InputStreamDataInput;
import shadow.lucene9.org.apache.lucene.store.NativeFSLockFactory;
import shadow.lucene9.org.apache.lucene.util.BytesRef;

@RequiredArgsConstructor
@Slf4j
public class SnapshotShardUnpacker {
    // Maximum number of concurrent file extractions
    private static final int MAX_CONCURRENT_EXTRACTIONS = Runtime.getRuntime().availableProcessors();
    
    private final SourceRepoAccessor repoAccessor;
    private final Set<ShardFileInfo> filesToUnpack;
    private final Path targetDirectory;
    private final String indexId;
    private final int shardId;

    @RequiredArgsConstructor
    public static class Factory {
        private final SourceRepoAccessor repoAccessor;
        private final Path luceneFilesBasePath;

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
                shardId
            );
        }
    }

    /**
     * Unpacks a single file from the shard.
     */
    private Mono<Void> unpackFile(FSDirectory primaryDirectory, ShardFileInfo fileMetadata) {
        return Mono.fromRunnable(() -> {
            try {
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
                            var stream = new PartSliceStream(
                                repoAccessor,
                                fileMetadata,
                                indexId,
                                shardId
                            )
                        ) {
                            var inputStream = new InputStreamDataInput(stream);
                            indexOutput.copyBytes(inputStream, fileMetadata.getLength());
                        }
                    }
                }
            } catch (Exception e) {
                var message = "Failed to unpack file: " + fileMetadata.getPhysicalName();
                log.atError()
                    .setMessage("{}")
                    .addArgument(message)
                    .setCause(e)
                    .log();
                throw new CouldNotUnpackShard(message, e);
            }
        });
    }

    public Path unpack() {
        try {
            // Some constants
            NativeFSLockFactory lockFactory = NativeFSLockFactory.INSTANCE;

            // Create the directory for the shard's lucene files
            Files.createDirectories(targetDirectory);

            try (FSDirectory primaryDirectory = FSDirectory.open(targetDirectory, lockFactory)) {
                log.atInfo()
                    .setMessage("Starting parallel unpacking of {} files for shard {} with {} threads")
                    .addArgument(filesToUnpack.size())
                    .addArgument(shardId)
                    .addArgument(MAX_CONCURRENT_EXTRACTIONS)
                    .log();

                // Use Flux to process files in parallel with controlled concurrency
                Flux.fromIterable(filesToUnpack)
                    .flatMap(
                        fileMetadata -> unpackFile(primaryDirectory, fileMetadata)
                            .subscribeOn(Schedulers.boundedElastic()),
                        MAX_CONCURRENT_EXTRACTIONS
                    )
                    .blockLast();

                log.atInfo()
                    .setMessage("Successfully unpacked {} files for shard {}")
                    .addArgument(filesToUnpack.size())
                    .addArgument(shardId)
                    .log();
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
