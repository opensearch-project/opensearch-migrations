package org.opensearch.migrations.transformation.entity;

import java.util.List;

public interface Cluster extends Entity {
    List<Index> getImmutableIndices();
}