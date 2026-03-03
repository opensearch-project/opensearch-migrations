/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package org.opensearch.migrations.transform.shim;

import java.util.function.Supplier;

import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.ThreadSafeTransformerWrapper;

import lombok.extern.slf4j.Slf4j;

/**
 * An {@link IJsonTransformer} that can atomically swap its underlying transformer at runtime.
 * Each delegate is wrapped in {@link ThreadSafeTransformerWrapper} for thread safety.
 */
@Slf4j
public class ReloadableTransformer implements IJsonTransformer {
    private volatile IJsonTransformer delegate;

    public ReloadableTransformer(Supplier<IJsonTransformer> supplier) {
        this.delegate = new ThreadSafeTransformerWrapper(supplier);
    }

    @Override
    public Object transformJson(Object input) {
        return delegate.transformJson(input);
    }

    /** Atomically swap the underlying transformer. The old delegate is not closed here
     *  because ThreadSafeTransformerWrapper uses ThreadLocal — closing it would only
     *  affect the current thread. Old instances are cleaned up by the GC via Cleaner. */
    public void reload(Supplier<IJsonTransformer> newSupplier) {
        this.delegate = new ThreadSafeTransformerWrapper(newSupplier);
        log.info("Transformer reloaded");
    }

    @Override
    public void close() throws Exception {
        delegate.close();
    }
}
