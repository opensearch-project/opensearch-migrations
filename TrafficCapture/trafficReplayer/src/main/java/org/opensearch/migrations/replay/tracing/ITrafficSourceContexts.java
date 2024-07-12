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

    interface ITrafficSourceContext extends IScopedInstrumentationAttributes {}

    interface IReadChunkContext extends ITrafficSourceContext {
        String ACTIVITY_NAME = ActivityNames.READ_NEXT_TRAFFIC_CHUNK;

        @Override
        default String getActivityName() {
            return ACTIVITY_NAME;
        }

        IBackPressureBlockContext createBackPressureContext();

        IKafkaConsumerContexts.IPollScopeContext createPollContext();

        IKafkaConsumerContexts.ICommitScopeContext createCommitContext();
    }

    interface IBackPressureBlockContext extends ITrafficSourceContext {
        String ACTIVITY_NAME = ActivityNames.BACK_PRESSURE_BLOCK;

        @Override
        default String getActivityName() {
            return ACTIVITY_NAME;
        }

        IWaitForNextSignal createWaitForSignalContext();

        IKafkaConsumerContexts.ITouchScopeContext createNewTouchContext();

        IKafkaConsumerContexts.ICommitScopeContext createCommitContext();
    }

    interface IWaitForNextSignal extends ITrafficSourceContext {
        String ACTIVITY_NAME = ActivityNames.WAIT_FOR_NEXT_BACK_PRESSURE_CHECK;

        default String getActivityName() {
            return ACTIVITY_NAME;
        }
    }
}
