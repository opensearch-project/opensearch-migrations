package org.opensearch.migrations.transform.shim.tracing;

import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

public interface IShimProxyContexts {

    class ActivityNames {
        private ActivityNames() {}
        public static final String SHIM_REQUEST = "shimRequest";
    }

    interface IRequestContext extends IScopedInstrumentationAttributes {
        String ACTIVITY_NAME = ActivityNames.SHIM_REQUEST;
    }
}
