package org.opensearch.migrations.transform.shim.netty;

import io.netty.util.AttributeKey;

/**
 * Channel attributes shared between pipeline handlers in the TransformationShim.
 */
public final class ShimChannelAttributes {
    /** Whether the original client request was keep-alive. */
    public static final AttributeKey<Boolean> KEEP_ALIVE =
        AttributeKey.valueOf("shim.keepAlive");

    private ShimChannelAttributes() {}
}
