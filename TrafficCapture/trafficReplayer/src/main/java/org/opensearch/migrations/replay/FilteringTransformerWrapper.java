package org.opensearch.migrations.replay;

import org.opensearch.migrations.transform.IJsonPredicate;
import org.opensearch.migrations.transform.IJsonTransformer;

import lombok.extern.slf4j.Slf4j;

/**
 * Wraps an {@link IJsonTransformer} with a predicate check. When the predicate
 * rejects (returns false), throws {@link RequestFilteredException}.
 */
@Slf4j
class FilteringTransformerWrapper implements IJsonTransformer {
    private final IJsonTransformer delegate;
    private final IJsonPredicate requestFilter;

    FilteringTransformerWrapper(IJsonTransformer delegate, IJsonPredicate requestFilter) {
        this.delegate = delegate;
        this.requestFilter = requestFilter;
    }

    @Override
    public Object transformJson(Object incomingJson) {
        if (!requestFilter.test(incomingJson)) {
            log.atInfo().setMessage("Request filtered out by predicate, skipping").log();
            throw new RequestFilteredException("Request rejected by request filter predicate");
        }
        return delegate.transformJson(incomingJson);
    }

    @Override
    public void close() throws Exception {
        delegate.close();
    }
}
