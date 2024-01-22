package org.opensearch.migrations.trafficcapture.netty.tracing;

import org.opensearch.migrations.tracing.IWithStartTimeAndAttributes;
import org.opensearch.migrations.tracing.IWithTypedEnclosingScope;
import org.opensearch.migrations.tracing.commoncontexts.IHttpTransactionContext;

public abstract class IWireCaptureContexts {

    public static class MetricNames {
        public static final String UNREGISTERED = "unregistered";
        public static final String REMOVED = "removed";

        public static final String BLOCKING_REQUEST = "blockingRequest";
        public static final String CAPTURE_SUPPRESSED = "captureSuppressed";
        public static final String FULL_REQUEST = "fullRequest";
        public static final String BYTES_READ = "bytesRead";
        public static final String BYTES_WRITTEN = "bytesWritten";
    }

    public interface ICapturingConnectionContext
            extends org.opensearch.migrations.tracing.commoncontexts.IConnectionContext {
        IHttpMessageContext createInitialRequestContext();

        void onUnregistered();

        void onRemoved();
    }

    public interface IHttpMessageContext
            extends IHttpTransactionContext,
                    IWithStartTimeAndAttributes,
                    IWithTypedEnclosingScope<ICapturingConnectionContext>
    {
        IBlockingContext createBlockingContext();

        IWaitingForResponseContext createWaitingForResponseContext();

        IResponseContext createResponseContext();

        IRequestContext createNextRequestContext();
    }

    public interface IRequestContext extends IHttpMessageContext {
        String ACTIVITY_NAME = "gatheringRequest";

        default String getActivityName() {
            return ACTIVITY_NAME;
        }

        void onBlockingRequest();

        void onCaptureSuppressed();

        void onFullyParsedRequest();

        void onBytesRead(int size);
    }

    public interface IBlockingContext extends IHttpMessageContext {
        String ACTIVITY_NAME = "blocked";

        default String getActivityName() {
            return ACTIVITY_NAME;
        }
    }

    public interface IWaitingForResponseContext extends IHttpMessageContext {
        String ACTIVITY_NAME = "waitingForResponse";

        default String getActivityName() {
            return ACTIVITY_NAME;
        }
    }

    public interface IResponseContext extends IHttpMessageContext {
        String ACTIVITY_NAME = "gatheringResponse";

        default String getActivityName() {
            return ACTIVITY_NAME;
        }

        void onBytesWritten(int size);
    }
}
