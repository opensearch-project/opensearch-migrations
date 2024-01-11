package org.opensearch.migrations.replay.tracing;

import org.opensearch.migrations.tracing.IInstrumentConstructor;
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

    interface ITrafficSourceContext<S extends IInstrumentConstructor> extends IScopedInstrumentationAttributes<S> {
        String SCOPE_NAME = ScopeNames.TRAFFIC_SCOPE;
        @Override default String getScopeName() { return SCOPE_NAME; }

    }
    interface IReadChunkContext<S extends IInstrumentConstructor> extends ITrafficSourceContext<S> {
        String ACTIVITY_NAME = ActivityNames.READ_NEXT_TRAFFIC_CHUNK;
        @Override
        default String getActivityName() { return ACTIVITY_NAME; }
    }
    interface IBackPressureBlockContext<S extends IInstrumentConstructor> extends ITrafficSourceContext<S> {
        String ACTIVITY_NAME = ActivityNames.BACK_PRESSURE_BLOCK;
        @Override
        default String getActivityName() { return ACTIVITY_NAME; }
    }
    interface IWaitForNextSignal<S extends IInstrumentConstructor> extends ITrafficSourceContext<S> {
        String ACTIVITY_NAME = ActivityNames.WAIT_FOR_NEXT_BACK_PRESSURE_CHECK;
        default String getActivityName() { return ACTIVITY_NAME; }
    }
}
