package org.opensearch.migrations.trafficcapture.netty.tracing;

import lombok.Getter;
import org.opensearch.migrations.tracing.DirectNestedSpanContext;
import org.opensearch.migrations.tracing.IWithStartTimeAndAttributes;
import org.opensearch.migrations.tracing.commoncontexts.IConnectionContext;
import org.opensearch.migrations.tracing.commoncontexts.IHttpTransactionContext;

import java.time.Instant;

public class HttpMessageContext extends DirectNestedSpanContext<IConnectionContext>
        implements IHttpTransactionContext, IWithStartTimeAndAttributes {
    public static final String SCOPE_NAME = "CapturingHttpHandler";

    public static final String GATHERING_REQUEST = "gatheringRequest";
    public static final String BLOCKED = "blocked";
    public static final String WAITING_FOR_RESPONSE = "waitingForResponse";
    public static final String GATHERING_RESPONSE = "gatheringResponse";

    public enum HttpTransactionState {
        REQUEST,
        INTERNALLY_BLOCKED,
        WAITING,
        RESPONSE
    }

    @Getter
    final long sourceRequestIndex;
    @Getter
    final HttpTransactionState state;

    static String getSpanLabelForState(HttpMessageContext.HttpTransactionState state) {
        switch (state) {
            case REQUEST:
                return GATHERING_REQUEST;
            case INTERNALLY_BLOCKED:
                return BLOCKED;
            case WAITING:
                return WAITING_FOR_RESPONSE;
            case RESPONSE:
                return GATHERING_RESPONSE;
            default:
                throw new IllegalStateException("Unknown enum value: "+state);
        }
    }


    public HttpMessageContext(IConnectionContext enclosingScope, long sourceRequestIndex, HttpTransactionState state) {
        super(enclosingScope);
        this.sourceRequestIndex = sourceRequestIndex;
        this.state = state;
        initializeSpan();
    }

    @Override
    public String getActivityName() {
        return getSpanLabelForState(state);
    }
    @Override
    public String getScopeName() { return SCOPE_NAME; }
}
