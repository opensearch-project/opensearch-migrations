package org.opensearch.migrations.bulkload.common;

/**
 * @deprecated Use {@link SourceRepoAccessor#SourceRepoAccessor(SourceRepo)} directly.
 * This class exists only for backward compatibility.
 */
@Deprecated
public class DefaultSourceRepoAccessor extends SourceRepoAccessor {
    public DefaultSourceRepoAccessor(SourceRepo repo) {
        super(repo);
    }
}
