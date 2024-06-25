package org.opensearch.migrations.transformation.entity;

public interface Doc extends Entity {
    Index getImmutableIndex();
}