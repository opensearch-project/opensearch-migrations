package org.opensearch.migrations.tracing;

import java.util.Comparator;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.Stream;

public class ActiveContextTracker implements IContextTracker {
    final ConcurrentSkipListSet<IScopedInstrumentationAttributes> orderedScopes;

    public ActiveContextTracker() {
        orderedScopes = makeScopeSkipList();
    }

    static ConcurrentSkipListSet<IScopedInstrumentationAttributes> makeScopeSkipList() {
        return new ConcurrentSkipListSet<>(
            Comparator.comparingLong(IWithStartTimeAndAttributes::getStartTimeNano)
                .thenComparingInt(System::identityHashCode)
        );
    }

    @Override
    public void onContextCreated(IScopedInstrumentationAttributes scopedContext) {
        orderedScopes.add(scopedContext);
    }

    @Override
    public void onContextClosed(IScopedInstrumentationAttributes scopedContext) {
        orderedScopes.remove(scopedContext);
    }

    public Stream<IScopedInstrumentationAttributes> getActiveScopesByAge() {
        return orderedScopes.stream();
    }

    public long size() {
        return orderedScopes.size();
    }
}
