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
    // Concurrency for file extractions — I/O-bound (S3 downloads), not CPU-bound,
    // so use a fixed value higher than availableProcessors() to saturate network bandwidth.
    private static final int MAX_CONCURRENT_EXTRACTIONS = 16;
    
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
     * Unpacks a single file from the shard. A file already present at its full length is skipped, so
     * unpacking a shard twice is a no-op rather than a failure; a short one is left over from an
     * interrupted attempt and is rewritten.
     */
    private Mono<Void> unpackFile(FSDirectory primaryDirectory, ShardFileInfo fileMetadata) {
        return Mono.fromRunnable(() -> {
            try {
                var existing = targetDirectory.resolve(fileMetadata.getPhysicalName());
                if (Files.exists(existing)) {
                    if (Files.size(existing) == expectedLengthOf(fileMetadata)) {
                        log.atDebug().setMessage("Already unpacked, skipping: {}")
                            .addArgument(fileMetadata::getPhysicalName)
                            .log();
                        return;
                    }
                    log.atWarn().setMessage("Re-unpacking truncated file from an earlier attempt: {}")
                        .addArgument(fileMetadata::getPhysicalName)
                        .log();
                    Files.delete(existing);
                }

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

    /** Metadata-hash files ("v__") carry the hash inline; everything else is the blob's length. */
    private static long expectedLengthOf(ShardFileInfo fileMetadata) {
        if (fileMetadata.getName().startsWith("v__")) {
            var hash = fileMetadata.getMetaHash();
            return hash == null ? -1 : hash.length;
        }
        return fileMetadata.getLength();
    }

    public Path unpack() {
        try {
            // Some constants
            NativeFSLockFactory lockFactory = NativeFSLockFactory.INSTANCE;

            // Create the directory for the shard's lucene files
            Files.createDirectories(targetDirectory);

            try (FSDirectory primaryDirectory = FSDirectory.open(targetDirectory, lockFactory)) {
                long totalBytes = filesToUnpack.stream().mapToLong(ShardFileInfo::getLength).sum();
                log.atInfo()
                    .setMessage("Starting parallel unpacking of {} files ({} MB) for shard {} with concurrency {}")
                    .addArgument(filesToUnpack.size())
                    .addArgument(totalBytes / (1024 * 1024))
                    .addArgument(shardId)
                    .addArgument(MAX_CONCURRENT_EXTRACTIONS)
                    .log();

                final int[] completedFiles = { 0 };
                int totalFiles = filesToUnpack.size();

                // Use Flux to process files in parallel with controlled concurrency.
                // Use CountDownLatch instead of blockLast()/block() to avoid Reactor's
                // blocking detection when called from non-blocking scheduler threads.
                unpackFilesInParallel(primaryDirectory, completedFiles, totalFiles);

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

    private void unpackFilesInParallel(FSDirectory primaryDirectory, int[] completedFiles, int totalFiles) {
        var latch = new java.util.concurrent.CountDownLatch(1);
        var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Flux.fromIterable(filesToUnpack)
            .flatMap(
                fileMetadata -> unpackFile(primaryDirectory, fileMetadata)
                    .doOnSuccess(v -> {
                        int done = ++completedFiles[0];
                        if (done % 5 == 0 || done == totalFiles) {
                            log.info("Shard {} unpack progress: {}/{} files", shardId, done, totalFiles);
                        }
                    })
                    .subscribeOn(Schedulers.boundedElastic()),
                MAX_CONCURRENT_EXTRACTIONS
            )
            .subscribe(
                v -> {},
                t -> { error.set(t); latch.countDown(); },
                latch::countDown
            );
        try {
            latch.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new CouldNotUnpackShard("Interrupted while unpacking shard " + shardId, ie);
        }
        if (error.get() != null) {
            var cause = error.get();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new CouldNotUnpackShard("File unpacking failed for shard " + shardId, (Exception) cause);
        }
    }

    public static class CouldNotUnpackShard extends RfsException implements SnapshotReadFailure {
        public CouldNotUnpackShard(String message, Exception e) {
            super(message, e);
        }
    }
}
