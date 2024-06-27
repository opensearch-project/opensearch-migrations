package org.opensearch.migrations.transformation.entity;

import java.util.List;

public interface Index extends Entity {
    Cluster getImmutableCluster();
    List<Doc> getImmutableDocs();
}