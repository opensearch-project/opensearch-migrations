package org.opensearch.migrations.tracing;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Allocates process-unique context IDs without a shared atomic operation for every context.
 */
final class ContextIdAllocator {
    static final int DEFAULT_BLOCK_SIZE = 65_536;

    private final int blockSize;
    private final AtomicLong nextBlockStart;
    // Each thread's cursor must outlive every call so its reserved block is consumed rather than abandoned;
    // the cursor holds only two longs, so retaining it for the thread's lifetime pins nothing else.
    @SuppressWarnings("java:S5164")
    private final ThreadLocal<BlockCursor> localBlock = ThreadLocal.withInitial(BlockCursor::new);

    ContextIdAllocator() {
        this(DEFAULT_BLOCK_SIZE, new AtomicLong());
    }

    ContextIdAllocator(int blockSize, AtomicLong nextBlockStart) {
        if (blockSize <= 0) {
            throw new IllegalArgumentException("blockSize must be positive");
        }
        this.blockSize = blockSize;
        this.nextBlockStart = nextBlockStart;
    }

    long nextId() {
        var block = localBlock.get();
        if (block.nextId == block.endExclusive) {
            var start = nextBlockStart.getAndAdd(blockSize);
            block.nextId = start;
            block.endExclusive = Math.addExact(start, blockSize);
        }
        return block.nextId++;
    }

    private static class BlockCursor {
        private long nextId;
        private long endExclusive;
    }
}
