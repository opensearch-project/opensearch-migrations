package com.rfs.tracing;

import org.opensearch.migrations.metadata.tracing.IMetadataMigrationContexts;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

public abstract class IRfsContexts {
    public static class ActivityNames {
        private ActivityNames() {}

        public static final String HTTP_REQUEST = "httpRequest";
        public static final String CHECK_THEN_PUT_REQUESTS = "checkThenPutRequest";
    }

    public static class MetricNames {
        private MetricNames() {}
        public static final String BYTES_READ = "bytesRead";
        public static final String BYTES_SENT = "bytesSent";
    }

    public interface IRequestContext extends IScopedInstrumentationAttributes {
        String ACTIVITY_NAME = ActivityNames.HTTP_REQUEST;

        void addBytesSent(int i);
        void addBytesRead(int i);
    }

    public interface ICheckedIdempotentPutRequestContext extends IScopedInstrumentationAttributes {
        String ACTIVITY_NAME = ActivityNames.CHECK_THEN_PUT_REQUESTS;
        IRequestContext createCheckRequestContext();
        IRequestContext createPutContext();
    }

    public interface ICreateSnapshotContext extends IScopedInstrumentationAttributes {
        String ACTIVITY_NAME = IMetadataMigrationContexts.ActivityNames.CREATE_SNAPSHOT;

        IRequestContext createRegisterRequest();

        IRequestContext createSnapshotContext();

        IRequestContext createGetSnapshotContext();
    }

}
