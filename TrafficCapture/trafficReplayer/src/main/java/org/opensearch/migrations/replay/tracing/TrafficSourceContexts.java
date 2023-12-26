package org.opensearch.migrations.replay.tracing;

import lombok.NonNull;
import org.opensearch.migrations.tracing.DirectNestedSpanContext;
import org.opensearch.migrations.tracing.IInstrumentationAttributes;

public class TrafficSourceContexts {

    private TrafficSourceContexts() {}

    public static class ReadChunkContext<T extends IInstrumentationAttributes>
            extends DirectNestedSpanContext<T>
            implements ITrafficSourceContexts.IReadChunkContext
    {
        public ReadChunkContext(T enclosingScope) {
            super(enclosingScope);
            setCurrentSpan();
        }
    }

    public static class BackPressureBlockContext
            extends DirectNestedSpanContext<ITrafficSourceContexts.IReadChunkContext>
            implements ITrafficSourceContexts.IBackPressureBlockContext
    {
        public BackPressureBlockContext(@NonNull ITrafficSourceContexts.IReadChunkContext enclosingScope) {
            super(enclosingScope);
            setCurrentSpan();
        }
    }

    public static class WaitForNextSignal
            extends DirectNestedSpanContext<ITrafficSourceContexts.IBackPressureBlockContext>
            implements ITrafficSourceContexts.IWaitForNextSignal {
        public WaitForNextSignal(@NonNull ITrafficSourceContexts.IBackPressureBlockContext enclosingScope) {
            super(enclosingScope);
            setCurrentSpan();
        }
    }

}
