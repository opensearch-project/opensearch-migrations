package org.opensearch.migrations.bulkload.tracing;

import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

public interface IRfsContexts {
    class ActivityNames {
        private ActivityNames() {}

        public static final String HTTP_REQUEST = "httpRequest";
        public static final String CHECK_THEN_PUT_REQUESTS = "checkThenPutRequest";
        public static final String DELTA_STREAM_CALCULATION = "deltaStreamCalculation";
    }

    class MetricNames {
        private MetricNames() {}

        public static final String BYTES_READ = "bytesRead";
        public static final String BYTES_SENT = "bytesSent";
    }

    interface IRequestContext extends IScopedInstrumentationAttributes {
        String ACTIVITY_NAME = ActivityNames.HTTP_REQUEST;

        void addBytesSent(int i);

        void addBytesRead(int i);
    }

    interface ICheckedIdempotentPutRequestContext extends IScopedInstrumentationAttributes {
        String ACTIVITY_NAME = ActivityNames.CHECK_THEN_PUT_REQUESTS;

        IRequestContext createCheckRequestContext();

        IRequestContext createPutContext();
    }

    interface ICreateSnapshotContext extends IScopedInstrumentationAttributes {
        String ACTIVITY_NAME = "createSnapshot";

        IRequestContext createRegisterRequest();

        IRequestContext createSnapshotContext();

        IRequestContext createGetSnapshotContext();
    }

    interface IDeltaStreamContext extends IScopedInstrumentationAttributes {
        String ACTIVITY_NAME = ActivityNames.DELTA_STREAM_CALCULATION;

        void recordSegmentsSeen(long count);

        void recordDeltaAdditions(long count);

        void recordDeltaDeletions(long count);
    }

}
