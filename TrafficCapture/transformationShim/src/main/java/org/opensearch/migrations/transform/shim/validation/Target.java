package org.opensearch.migrations.transform.shim.validation;

import java.net.URI;
import java.util.function.Supplier;

import org.opensearch.migrations.transform.IJsonTransformer;

import io.netty.channel.ChannelHandler;

/**
 * A named backend target with optional per-target request/response transforms and auth.
 * A target with null transforms is a passthrough.
 */
public record Target(
    String name,
    URI uri,
    IJsonTransformer requestTransform,
    IJsonTransformer responseTransform,
    Supplier<ChannelHandler> authHandlerSupplier
) {
    /** Convenience constructor for a simple passthrough target. */
    public Target(String name, URI uri) {
        this(name, uri, null, null, null);
    }
}
