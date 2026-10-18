package org.opensearch.migrations.bulkload.common;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Names of index settings, relative to the "index." prefix, that the index creators remove before
 * creating an index on the target.
 *
 * <ul>
 *   <li>{@link #EXACT} / {@link #PREFIXES}: identity and version bookkeeping of the source cluster's
 *       copy of the index. Never valid on a target, so every index creator removes them.</li>
 *   <li>{@link #EXTENDED_EXACT} / {@link #EXTENDED_PREFIXES}: the above plus write blocks, node
 *       allocation rules, and whole "version", "mapping" and "mapper" objects. Used by the pipeline's
 *       index creator, which matches top-level keys as well as full setting names.</li>
 * </ul>
 */
public final class InternalIndexSettingNames {
    /** Source-internal settings matched by exact name. */
    public static final Set<String> EXACT = Set.of(
        "creation_date",
        "uuid",
        "provided_name",
        "history.uuid",
        "verified_before_close"
    );

    /** Source-internal settings matched by name prefix. */
    public static final Set<String> PREFIXES = Set.of(
        "version.",       // version.created, version.upgraded, ...
        "resize.source."  // resize.source.name, resize.source.uuid
    );

    /** {@link #EXACT} plus whole objects removed by the pipeline's index creator. */
    public static final Set<String> EXTENDED_EXACT = union(EXACT, Set.of(
        "version",
        "mapping",
        "mapper"
    ));

    /** {@link #PREFIXES} plus write blocks and node allocation rules removed by the pipeline's index creator. */
    public static final Set<String> EXTENDED_PREFIXES = union(PREFIXES, Set.of(
        "blocks.",                      // blocks.write, blocks.read_only, ...
        "resize.",                      // resize.source.name, resize.source.uuid
        "routing.allocation.include.",
        "routing.allocation.require.",
        "routing.allocation.exclude."
    ));

    private InternalIndexSettingNames() {}

    private static Set<String> union(Set<String> base, Set<String> additions) {
        return Stream.concat(base.stream(), additions.stream()).collect(Collectors.toUnmodifiableSet());
    }
}
