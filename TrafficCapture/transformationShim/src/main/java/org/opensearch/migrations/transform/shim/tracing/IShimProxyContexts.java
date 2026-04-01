package org.opensearch.migrations.transform.shim.tracing;

import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

public interface IShimProxyContexts {

    class ActivityNames {
        private ActivityNames() {}
        public static final String SHIM_REQUEST = "shimRequest";
        public static final String TARGET_DISPATCH = "targetDispatch";
    }

    class MetricNames {
        private MetricNames() {}
        public static final String TARGET_BYTES_SENT = "targetBytesSent";
        public static final String TARGET_BYTES_RECEIVED = "targetBytesReceived";
    }

    interface IRequestContext extends IScopedInstrumentationAttributes {
        String ACTIVITY_NAME = ActivityNames.SHIM_REQUEST;

        ITargetDispatchContext createTargetDispatchContext(String targetName);
    }

    interface ITargetDispatchContext extends IScopedInstrumentationAttributes {
        String ACTIVITY_NAME = ActivityNames.TARGET_DISPATCH;

        void addBytesSent(int bytes);
        void addBytesReceived(int bytes);
    }
}
