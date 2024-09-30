package org.opensearch.migrations.trafficcapture.netty.tracing;

import org.opensearch.migrations.tracing.IWithStartTimeAndAttributes;
import org.opensearch.migrations.tracing.IWithTypedEnclosingScope;
import org.opensearch.migrations.tracing.commoncontexts.IHttpTransactionContext;

public interface IWireCaptureContexts {

    class ActivityNames {
        private ActivityNames() {}

        public static final String BLOCKED = "blocked";
        public static final String GATHERING_REQUEST = "gatheringRequest";
        public static final String WAITING_FOR_RESPONSE = "waitingForResponse";
        public static final String GATHERING_RESPONSE = "gatheringResponse";
    }

    class MetricNames {
        private MetricNames() {}

        public static final String UNREGISTERED = "unregistered";
        public static final String REMOVED = "removed";

        public static final String BLOCKING_REQUEST = "blockingRequest";
        public static final String CAPTURE_SUPPRESSED = "captureSuppressed";
        public static final String FULL_REQUEST = "fullRequest";
        public static final String BYTES_READ = "bytesRead";
        public static final String BYTES_WRITTEN = "bytesWritten";
    }

    interface ICapturingConnectionContext
        extends
            org.opensearch.migrations.tracing.commoncontexts.IConnectionContext {
        IHttpMessageContext createInitialRequestContext();

        void onUnregistered();

        void onRemoved();
    }

    interface IHttpMessageContext
        extends
            IHttpTransactionContext,
            IWithStartTimeAndAttributes,
            IWithTypedEnclosingScope<ICapturingConnectionContext> {
        IBlockingContext createBlockingContext();

        IWaitingForResponseContext createWaitingForResponseContext();

        IResponseContext createResponseContext();

        IRequestContext createNextRequestContext();
    }

    interface IRequestContext extends IHttpMessageContext {
        String ACTIVITY_NAME = ActivityNames.GATHERING_REQUEST;

        default String getActivityName() {
            return ACTIVITY_NAME;
        }

        void onBlockingRequest();

        void onCaptureSuppressed();

        void onFullyParsedRequest();

        void onBytesRead(int size);
    }

    interface IBlockingContext extends IHttpMessageContext {
        String ACTIVITY_NAME = ActivityNames.BLOCKED;

        default String getActivityName() {
            return ACTIVITY_NAME;
        }
    }

    interface IWaitingForResponseContext extends IHttpMessageContext {
        String ACTIVITY_NAME = ActivityNames.WAITING_FOR_RESPONSE;

        default String getActivityName() {
            return ACTIVITY_NAME;
        }
    }

    interface IResponseContext extends IHttpMessageContext {
        String ACTIVITY_NAME = ActivityNames.GATHERING_RESPONSE;

        default String getActivityName() {
            return ACTIVITY_NAME;
        }

        void onBytesWritten(int size);
    }
}
