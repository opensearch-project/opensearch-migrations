package org.opensearch.migrations.tracing;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.stream.Collectors;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BacktracingContextTracker implements IContextTracker, AutoCloseable {
    private static class ExceptionForStackTracingOnly extends Exception {}

    @Getter
    public static class CallDetails {
        private final ExceptionForStackTracingOnly createStackException;
        private ExceptionForStackTracingOnly closeStackException;

        public CallDetails() {
            createStackException = new ExceptionForStackTracingOnly();
        }
    }

    private final Map<IScopedInstrumentationAttributes, CallDetails> scopedContextToCallDetails = new WeakHashMap<>();
    private final Object lockObject = new Object();
    private boolean isClosed;

    public void onCreated(IScopedInstrumentationAttributes ctx) {
        synchronized (lockObject) {
            if (isClosed) {
                return;
            }
            var priorValue = scopedContextToCallDetails.putIfAbsent(ctx, new CallDetails());
            if (priorValue != null) {
                var priorKey = scopedContextToCallDetails.entrySet().stream().findFirst().get().getKey();
                if (priorKey.equals(ctx)) {
                    log.atError().setMessage("Trying to re-add the same context ({}) back into this context tracker")
                        .addArgument(ctx).log();
                }
            }
            assert priorValue == null;
        }
    }

    public void onClosed(IScopedInstrumentationAttributes ctx) {
        synchronized (lockObject) {
            if (isClosed) {
                return;
            }
            var newExceptionStack = new ExceptionForStackTracingOnly();
            var oldCallDetails = scopedContextToCallDetails.get(ctx);
            assert oldCallDetails != null;
            final var oldE = oldCallDetails.closeStackException;
            if (oldE != null) {
                log.atError().setCause(newExceptionStack).setMessage("Close is being called here").log();
                log.atError().setCause(oldE).setMessage("... but close was already called here").log();
                assert oldE == null;
            }
            oldCallDetails.closeStackException = new ExceptionForStackTracingOnly();
        }
    }

    public Map<IScopedInstrumentationAttributes, CallDetails> getAllRemainingActiveScopes() {
        synchronized (lockObject) {
            return scopedContextToCallDetails.entrySet()
                .stream()
                // filter away items that were closed but not cleared yet (since it's a weak map)
                .filter(kvp -> kvp.getValue().closeStackException == null)
                // make a copy since we're in a synchronized block
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }
    }

    public void close() {
        synchronized (lockObject) {
            scopedContextToCallDetails.clear();
            isClosed = true;
        }
    }
}
