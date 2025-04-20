package org.opensearch.migrations.transform;

import java.lang.ref.Cleaner;
import java.util.function.Supplier;

import lombok.extern.slf4j.Slf4j;

/**
 * A thread-safe wrapper around {@link IJsonTransformer} that ensures each thread
 * has its own transformer instance via {@link ThreadLocal}.
 * <p>
 * This design is useful when the transformer is stateful or not thread-safe.
 * Each thread gets a lazily-initialized transformer from the provided supplier.
 *
 * <h2>Resource Management</h2>
 * The wrapper implements {@link AutoCloseable}, and callers are expected to invoke
 * {@link #close()} when a thread is finished using its transformer. This ensures
 * deterministic release of resources and avoids memory leaks in thread pools.
 *
 * <h2>Cleaner Integration</h2>
 * As a fallback, this class also registers each transformer with a shared {@link java.lang.ref.Cleaner}.
 * If a thread dies or the transformer becomes unreachable without an explicit {@link #close()} call,
 * the cleaner will invoke a cleanup task to call {@link IJsonTransformer#close()}.
 *
 * <h2>Thread-local semantics</h2>
 * Calling {@link #close()} only affects the transformer instance associated with the
 * current thread. It does not close transformers used by other threads.
 */
@Slf4j
public class ThreadSafeTransformerWrapper implements IJsonTransformer {

    public static final Cleaner FALLBACK_CLEANER = Cleaner.create();

    /** Thread-local holding per-thread transformer wrappers */
    private final ThreadLocal<CloseTrackingTransformer> threadLocalHolder;

    /**
     * Constructs a new thread-safe transformer wrapper using the given supplier.
     *
     * @param transformerSupplier Supplier that produces a new {@link IJsonTransformer} for each thread.
     */
    public ThreadSafeTransformerWrapper(Supplier<IJsonTransformer> transformerSupplier) {
        this.threadLocalHolder = ThreadLocal.withInitial(() -> {
            var trackingTransformer = new CloseTrackingTransformer(transformerSupplier.get());
            // Register the tracking wrapper with the Cleaner. The cleaner will call cleanup() when the ThreadSafeTransformerWrapper is garbage collected
            FALLBACK_CLEANER.register(this, trackingTransformer::cleanup);
            return trackingTransformer;
        });
    }

    @Override
    public Object transformJson(Object input) {
        return threadLocalHolder.get().transformJson(input);
    }

    /**
     * Manually closes the transformer associated with the current thread.
     * This must be called prior to any calling thread being shutdown.
     */
    @Override
    public void close() {
        var transformer = threadLocalHolder.get();
        if (transformer != null) {
            transformer.close();
            threadLocalHolder.remove();
        }
    }

    /**
     * A close-tracking wrapper around an {@code IJsonTransformer} that monitors whether it has been closed.
     */
    private static class CloseTrackingTransformer implements IJsonTransformer {
        private final IJsonTransformer delegate;
        // Volatile flag for thread-safe check of the closed state.
        private volatile boolean closed = false;

        CloseTrackingTransformer(IJsonTransformer delegate) {
            this.delegate = delegate;
        }

        /**
         * Invoked by the Cleaner if the transformer is garbage-collected without an explicit close().
         * This checks whether close() was already called, and if not, performs cleanup.
         */
        private void cleanup() {
            if (!closed) {
                log.atWarn()
                    .setMessage("Fallback cleaner used to cleanup transformer {}. Explicit close() was not called.")
                    .addArgument(delegate)
                    .log();
                try {
                    delegate.close();
                } catch (Exception e) {
                    log.atError()
                        .setMessage("Exception during fallback cleaning of transformer {}.")
                        .addArgument(delegate)
                        .setCause(e)
                        .log();
                }
                closed = true;
            }
        }

        @Override
        public Object transformJson(Object input) {
            if (closed) {
                throw new IllegalStateException("Transformer is closed");
            }
            return delegate.transformJson(input);
        }

        /**
         * Explicitly closes the transformer, deregisters the cleaner, and marks the instance as closed.
         */
        @Override
        public synchronized void close() {
            if (!closed) {
                try {
                    delegate.close();
                } catch (Exception e) {
                    log.atError()
                        .setMessage("Failed to close transformer {}.")
                        .addArgument(delegate)
                        .setCause(e)
                        .log();
                } finally {
                    closed = true;
                }
            }
        }
    }
}
