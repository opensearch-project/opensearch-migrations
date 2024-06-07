package org.opensearch.migrations.transformation;

public interface Cluster extends Entity {
    ImmutableList<Index> getImmutableIndices();
}