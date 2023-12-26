package org.opensearch.migrations.replay.tracing;

import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

public interface ITrafficSourceContexts {
    class ScopeNames {
        private ScopeNames() {}
        public static final String TRAFFIC_SCOPE = "BlockingTrafficSource";
    }

    class ActivityNames {
        private ActivityNames() {}
        public static final String READ_NEXT_TRAFFIC_CHUNK = "readNextTrafficStreamChunk";
        public static final String BACK_PRESSURE_BLOCK = "backPressureBlock";
        public static final String WAIT_FOR_NEXT_BACK_PRESSURE_CHECK = "waitForNextBackPressureCheck";
    }

    interface ITrafficSourceContext extends IScopedInstrumentationAttributes {
        @Override
        default String getScopeName() { return ScopeNames.TRAFFIC_SCOPE; }
    }
    interface IReadChunkContext extends ITrafficSourceContext {
        @Override
        default String getActivityName() { return ActivityNames.READ_NEXT_TRAFFIC_CHUNK; }
    }
    interface IBackPressureBlockContext extends ITrafficSourceContext {
        @Override
        default String getActivityName() { return ActivityNames.BACK_PRESSURE_BLOCK; }
    }
    interface IWaitForNextSignal extends ITrafficSourceContext {
        default String getActivityName() { return ActivityNames.WAIT_FOR_NEXT_BACK_PRESSURE_CHECK; }
    }
}
