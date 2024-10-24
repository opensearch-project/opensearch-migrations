package org.opensearch.migrations.data.workloads;

import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Defines a set of indices, settings, and documents that can be added onto a cluster
 */
public interface Workload {
    /** Create an index for the workload with the default settings incorporated */
    ObjectNode createIndex(ObjectNode defaultSettings);

    /** Creates a stream of documents for this workload */
    Stream<ObjectNode> createDocs(int numDocs);

    /** The name(s) of the indices that should be created for this workload */
    List<String> indexNames();
}
