package org.opensearch.migrations.bulkload.common;

/**
 * Represents the type of an Amazon OpenSearch Serverless collection.
 * Different collection types have different capabilities and restrictions.
 */
public enum ServerlessCollectionType {
    /** Search collection - supports custom document IDs */
    SEARCH,
    /** Timeseries collection - does NOT support custom document IDs */
    TIMESERIES,
    /** Vector search collection - does NOT support custom document IDs */
    VECTOR,
    /** Not a serverless collection or type could not be determined */
    NONE;

    /**
     * Returns true if this collection type requires server-generated document IDs.
     * Both TIMESERIES and VECTOR collections do not support custom document IDs.
     */
    public boolean requiresServerGeneratedIds() {
        return this == TIMESERIES || this == VECTOR;
    }
}
