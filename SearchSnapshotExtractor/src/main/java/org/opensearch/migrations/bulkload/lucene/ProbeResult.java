package org.opensearch.migrations.bulkload.lucene;

/**
 * Result of probing a Lucene segment for a field's value across the
 * stored → doc_values → points/terms → constant tier chain (used by the
 * copy_to reverse-derivation pass in {@link SourceReconstructor}).
 *
 * <p>The two variants make the conversion contract explicit. Tiers that already produce a
 * mapping-shaped Java value (stored fields, doc_values, mapping-level constants) yield
 * {@link Final} and the caller writes them verbatim. The points/terms tier yields a
 * {@link Raw} carrying the untyped {@link RecoveredValue} so the caller can apply the
 * <em>source</em> field's mapping (the copy_to source's expected JSON shape), not the
 * target's.
 *
 * <p>Splitting the contract this way removes a dangerous cycle in the previous code: a value
 * pre-converted by the target mapping was re-fed to {@code convertFallbackValue} as if it were
 * still a raw points/terms result, where an unchecked {@code (List<byte[]>)} cast then crashed
 * on a {@code List<String>} produced by SORTED_SET doc_values.
 */
public sealed interface ProbeResult permits ProbeResult.Final, ProbeResult.Raw {

    /** Tier 1 / 2 / 4 hit: value already shaped by its target mapping; write verbatim. */
    record Final(Object value) implements ProbeResult {}

    /**
     * Tier 3 hit: untyped points/terms recovery. Caller applies the source field's mapping
     * via {@link SourceReconstructor#convertFallbackValue(RecoveredValue, FieldMappingInfo)}.
     */
    record Raw(RecoveredValue raw) implements ProbeResult {}
}
