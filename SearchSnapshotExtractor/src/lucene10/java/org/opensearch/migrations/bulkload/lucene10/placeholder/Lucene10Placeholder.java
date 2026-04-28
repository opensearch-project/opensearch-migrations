package org.opensearch.migrations.bulkload.lucene10.placeholder;

/**
 * Placeholder class to ensure the lucene10 source set has at least one compilable class.
 * <p>
 * The lucene9 source set carries OpenSearch k-NN codec shims (KNN80Codec, KNN9120Codec, etc.)
 * that are tightly coupled to Lucene 9 backward-codec APIs. Those shims are not required for
 * basic snapshot reading and have not been ported to Lucene 10 yet. When ES 9 / OS 3 snapshots
 * containing k-NN vector fields are encountered, the ported codec shims must be added here.
 */
public final class Lucene10Placeholder {
    private Lucene10Placeholder() {}
}
