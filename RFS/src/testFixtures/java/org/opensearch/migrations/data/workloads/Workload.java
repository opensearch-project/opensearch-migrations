package org.opensearch.migrations.data.workloads;

import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.node.ObjectNode;

public interface Workload {
    ObjectNode createIndex(ObjectNode defaultSettings);

    Stream<ObjectNode> createDocs(int numDocs);

    List<String> indexNames();
}
