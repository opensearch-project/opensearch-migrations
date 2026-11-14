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
        public static final String BULK_DOCUMENTS_FAILED = "bulkDocumentsFailed";
        public static final String BULK_OVERSIZED_OPERATIONS = "bulkOversizedOperations";
        public static final String DOCUMENTS_REJECTED_FOR_SIZE = "documentsRejectedForSize";
    }

    interface IRequestContext extends IScopedInstrumentationAttributes {
        String ACTIVITY_NAME = ActivityNames.HTTP_REQUEST;

        void addBytesSent(int i);

        void addBytesRead(int i);

        /**
         * Bulk documents the migration moved past without writing, by failure class ({@code NON_RETRYABLE} or
         * {@code RETRYABLE_EXHAUSTED}).
         */
        default void addBulkDocumentsFailed(String failureClass, long count) {}

        /**
         * One operation bigger than the bulk byte limit was sent in a request by itself; {@code path} is
         * {@code raw} (source bytes) or {@code transformed}.
         */
        default void addBulkOversizedOperation(String path) {}

        /** Documents not sent because they exceed maxDocumentSizeBytes. */
        default void addDocumentsRejectedForSize(long count) {}
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
