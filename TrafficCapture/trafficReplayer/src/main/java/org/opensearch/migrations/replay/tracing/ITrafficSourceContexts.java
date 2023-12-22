package org.opensearch.migrations.replay.tracing;

import org.opensearch.migrations.tracing.IInstrumentationAttributes;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

public interface ITrafficSourceContexts {
    String TELEMETRY_SCOPE_NAME = "BlockingTrafficSource";

    interface ITrafficSourceContext extends IScopedInstrumentationAttributes {
        @Override
        default String getScopeName() { return TELEMETRY_SCOPE_NAME; }
    }
    interface IReadChunkContext extends ITrafficSourceContext {}
    interface IBackPressureBlockContext extends ITrafficSourceContext {}
    interface IWaitForNextSignal extends ITrafficSourceContext {}
}
