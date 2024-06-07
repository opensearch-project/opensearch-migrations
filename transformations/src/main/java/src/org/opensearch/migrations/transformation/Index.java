package org.opensearch.migrations.transformation;

public interface Index extends Entity {
    Cluster getImmutableCluster();
    ImmutableList<Doc> getImmutableDocs();
}