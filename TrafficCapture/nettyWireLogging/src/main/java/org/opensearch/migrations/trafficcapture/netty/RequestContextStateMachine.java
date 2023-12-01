package org.opensearch.migrations.trafficcapture.netty;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.trafficcapture.netty.tracing.HttpMessageContext;
import org.opensearch.migrations.trafficcapture.tracing.ConnectionContext;

/**
 * This is a helper class so that we can emit metrics and traces for when we're
 * accumulating a request vs waiting for the next response, then repeating indefinitely.
 *
 * TODO - this may be a performance bottleneck and we should carefully evaluate it's utility.
 */
@Slf4j
public class RequestContextStateMachine {
    @Getter
    public final ConnectionContext connectionContext;
    @Getter
    HttpMessageContext currentRequestContext;

    public RequestContextStateMachine(ConnectionContext incoming) {
        connectionContext = incoming;
    }
}
