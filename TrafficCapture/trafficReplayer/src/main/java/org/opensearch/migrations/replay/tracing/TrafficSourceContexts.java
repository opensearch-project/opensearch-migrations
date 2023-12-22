package org.opensearch.migrations.replay.tracing;

import lombok.NonNull;
import org.opensearch.migrations.tracing.DirectNestedSpanContext;
import org.opensearch.migrations.tracing.IInstrumentationAttributes;

public class TrafficSourceContexts {

    public static class ReadChunkContext<T extends IInstrumentationAttributes>
            extends DirectNestedSpanContext<T>
            implements ITrafficSourceContexts.IReadChunkContext
    {
        public ReadChunkContext(T enclosingScope) {
            super(enclosingScope);
            setCurrentSpan("readNextTrafficStreamChunk");
        }
    }

    public static class BackPressureBlockContext
            extends DirectNestedSpanContext<ITrafficSourceContexts.IReadChunkContext>
            implements ITrafficSourceContexts.IBackPressureBlockContext
    {
        public BackPressureBlockContext(@NonNull ITrafficSourceContexts.IReadChunkContext enclosingScope) {
            super(enclosingScope);
            setCurrentSpan("backPressureBlock");
        }
    }

    public static class WaitForNextSignal
            extends DirectNestedSpanContext<ITrafficSourceContexts.IBackPressureBlockContext>
            implements ITrafficSourceContexts.IReadChunkContext {
        public WaitForNextSignal(@NonNull ITrafficSourceContexts.IBackPressureBlockContext enclosingScope) {
            super(enclosingScope);
            setCurrentSpan("waitForNextBackPressureCheck");
        }
    }

}
