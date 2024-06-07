package org.opensearch.migrations.transformation;

public interface Doc extends Entity {
    Index getImmutableIndex();
}