package org.opensearch.migrations.bulkload.common;

/**
 * @deprecated Use {@link SourceRepoAccessor#SourceRepoAccessor(SourceRepo)} directly.
 * This class exists only for backward compatibility.
 */
@Deprecated(since = "0.1.0", forRemoval = true)
@SuppressWarnings("java:S1133")
public class DefaultSourceRepoAccessor extends SourceRepoAccessor {
    public DefaultSourceRepoAccessor(SourceRepo repo) {
        super(repo);
    }
}
