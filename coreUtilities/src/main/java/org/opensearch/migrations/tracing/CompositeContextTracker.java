package org.opensearch.migrations.tracing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CompositeContextTracker implements IContextTracker {
    private final List<IContextTracker> trackers;

    public CompositeContextTracker(IContextTracker... trackers) {
        this.trackers = Arrays.stream(trackers).collect(Collectors.toUnmodifiableList());
    }

    public CompositeContextTracker(List<IContextTracker> trackers) {
        this.trackers = new ArrayList<>(trackers);
    }

    @Override
    public void onContextCreated(IScopedInstrumentationAttributes scopedContext) {
        trackers.forEach(ct -> ct.onContextCreated(scopedContext));
    }

    @Override
    public void onContextClosed(IScopedInstrumentationAttributes scopedContext) {
        trackers.forEach(ct -> ct.onContextClosed(scopedContext));
    }

    public Stream<IContextTracker> getTrackers() {
        return trackers.stream();
    }
}
