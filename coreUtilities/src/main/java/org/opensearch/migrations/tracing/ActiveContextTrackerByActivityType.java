package org.opensearch.migrations.tracing;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.Stream;

public class ActiveContextTrackerByActivityType implements IContextTracker {
    final ConcurrentHashMap<
        Class<IScopedInstrumentationAttributes>,
        ConcurrentSkipListSet<IScopedInstrumentationAttributes>> orderedScopesByScopeType;

    public ActiveContextTrackerByActivityType() {
        orderedScopesByScopeType = new ConcurrentHashMap<>();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onContextCreated(IScopedInstrumentationAttributes scopedContext) {
        orderedScopesByScopeType.computeIfAbsent(
            (Class<IScopedInstrumentationAttributes>) scopedContext.getClass(),
            c -> ActiveContextTracker.makeScopeSkipList()
        ).add(scopedContext);
    }

    @Override
    public void onContextClosed(IScopedInstrumentationAttributes scopedContext) {
        final var skipListByType = orderedScopesByScopeType.get(scopedContext.getClass());
        assert skipListByType != null : "expected to have already added the scope to the collection, "
            + "so the top-level class mapping should be present";
        skipListByType.remove(scopedContext);
    }

    public Stream<IScopedInstrumentationAttributes> getOldestActiveScopes(
        Class<IScopedInstrumentationAttributes> activityType
    ) {
        return Optional.ofNullable(orderedScopesByScopeType.getOrDefault(activityType, null))
            .stream()
            .flatMap(Collection::stream);
    }

    public Stream<Class<IScopedInstrumentationAttributes>> getActiveScopeTypes() {
        return orderedScopesByScopeType.entrySet()
            .stream()
            .filter(kvp -> !kvp.getValue().isEmpty())
            .map(Map.Entry::getKey);
    }

    public long numScopesFor(Class<IScopedInstrumentationAttributes> c) {
        return orderedScopesByScopeType.get(c).size();
    }
}
