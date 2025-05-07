package org.opensearch.migrations.transform;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ThreadSafeTransformerWrapperTest {

    // Dummy implementation for testing transformation and closure behavior.
    static class TestJsonTransformer implements IJsonTransformer {
        private boolean closed = false;

        @Override
        public Object transformJson(Object input) {
            // Simple transformation: convert String input to upper case.
            if (input instanceof String) {
                return ((String) input).toUpperCase();
            }
            return input;
        }

        @Override
        public void close() {
            closed = true;
        }

        public boolean isClosed() {
            return closed;
        }
    }

    // Test that transformJson correctly transforms input.
    @Test
    public void testTransformJson() {
        Supplier<IJsonTransformer> supplier = TestJsonTransformer::new;
        ThreadSafeTransformerWrapper wrapper = new ThreadSafeTransformerWrapper(supplier);
        String input = "test";
        Object result = wrapper.transformJson(input);
        Assertions.assertEquals("TEST", result, "The transformer should convert input to upper case.");
    }

    // Test that calling close() properly triggers the underlying transformer's close() method.
    @Test
    public void testCloseCallsUnderlyingClose() {
        TestJsonTransformer transformer = new TestJsonTransformer();
        // For simplicity, always return the same transformer instance.
        ThreadSafeTransformerWrapper wrapper = new ThreadSafeTransformerWrapper(() -> transformer);
        // Initialize thread-local transformer instance.
        wrapper.transformJson("dummy");
        wrapper.close();
        Assertions.assertTrue(transformer.isClosed(), "Underlying transformer should be marked as closed.");
    }

    // Test that each thread gets its own transformer by counting the total close() calls.
    @Test
    public void testThreadLocalTransformer() throws InterruptedException {
        AtomicInteger closeCount = new AtomicInteger(0);
        // Counting transformer: increments count when close() is invoked.
        class CountingTransformer implements IJsonTransformer {
            @Override
            public Object transformJson(Object input) {
                if (input instanceof String) {
                    return ((String) input).toUpperCase();
                }
                return input;
            }
            @Override
            public void close() {
                closeCount.incrementAndGet();
            }
        }

        ThreadSafeTransformerWrapper wrapper = new ThreadSafeTransformerWrapper(CountingTransformer::new);
        // Use transformer in main thread.
        wrapper.transformJson("foo");
        wrapper.close();  // Expected to increment count to 1.

        // Use transformer in a new thread.
        Thread thread = new Thread(() -> {
            wrapper.transformJson("bar");
            wrapper.close();  // Expected to increment count to 2.
        });
        thread.start();
        thread.join();

        Assertions.assertEquals(2, closeCount.get(), "Each thread should have its own transformer and call close once.");
    }

    static class CloseLatchTransformer implements IJsonTransformer {
        private final CountDownLatch latch;

        CloseLatchTransformer(CountDownLatch latch) {
            this.latch = latch;
        }

        @Override
        public Object transformJson(Object input) {
            return input;
        }

        @Override
        public void close() {
            latch.countDown();
        }
    }

    @Test
    public void testCleanerBehaviorWithLatch() throws InterruptedException {
        // Latch to wait for the cleanup (close()) to be called.
        CountDownLatch latch = new CountDownLatch(1);

        Supplier<IJsonTransformer> transformerSupplier = () -> new CloseLatchTransformer(latch);

        // Create and start a new thread that uses the transformer without explicitly calling close()
        Thread thread = new Thread(() -> {
            ThreadSafeTransformerWrapper wrapper = new ThreadSafeTransformerWrapper(transformerSupplier);
            // Initialize thread-local transformer instance.
            wrapper.transformJson("test");
        });
        thread.start();
        thread.join();

        // Force garbage collection to make thread-local data unreachable.
        System.gc();

        boolean closedByCleaner = latch.await(5, TimeUnit.SECONDS);

        Assertions.assertTrue(closedByCleaner, "Cleaner should have closed the transformer within 5 seconds.");
    }
}
