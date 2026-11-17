package org.opensearch.migrations.tracing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ContextIdAllocatorTest {

    @Test
    void allocationRemainsUniqueAcrossBlockBoundaries() {
        var nextBlockStart = new AtomicLong();
        var allocator = new ContextIdAllocator(4, nextBlockStart);
        var allocated = new ArrayList<Long>();

        for (int i = 0; i < 9; ++i) {
            allocated.add(allocator.nextId());
        }

        Assertions.assertEquals(allocated.size(), new HashSet<>(allocated).size());
        Assertions.assertEquals(12, nextBlockStart.get(), "three blocks should have been reserved");
    }

    @Test
    void sharedAtomicIsNotIncrementedForEachContext() {
        var nextBlockStart = new AtomicLong();
        var allocator = new ContextIdAllocator(4, nextBlockStart);

        allocator.nextId();
        Assertions.assertEquals(4, nextBlockStart.get());

        allocator.nextId();
        allocator.nextId();
        allocator.nextId();
        Assertions.assertEquals(4, nextBlockStart.get(), "IDs within a block must not touch the shared atomic");

        allocator.nextId();
        Assertions.assertEquals(8, nextBlockStart.get(), "the shared atomic advances only at a block boundary");
    }

    @Test
    void concurrentAllocationHasNoDuplicates() throws Exception {
        var allocator = new ContextIdAllocator(128, new AtomicLong());
        var allocated = ConcurrentHashMap.<Long>newKeySet();
        var threadCount = 8;
        var idsPerThread = 10_000;

        try (var executor = Executors.newFixedThreadPool(threadCount)) {
            var tasks = new ArrayList<java.util.concurrent.Callable<Void>>();
            for (int thread = 0; thread < threadCount; ++thread) {
                tasks.add(() -> {
                    for (int i = 0; i < idsPerThread; ++i) {
                        Assertions.assertTrue(allocated.add(allocator.nextId()), "duplicate context ID");
                    }
                    return null;
                });
            }
            for (var result : executor.invokeAll(tasks)) {
                result.get();
            }
        }

        Assertions.assertEquals(threadCount * idsPerThread, allocated.size());
    }
}
