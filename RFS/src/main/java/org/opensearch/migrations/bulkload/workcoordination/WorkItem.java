package org.opensearch.migrations.bulkload.workcoordination;

import java.io.Serializable;

import lombok.EqualsAndHashCode;
import lombok.Getter;

@EqualsAndHashCode
@Getter
public class WorkItem implements Serializable {
    private static final String SEPARATOR = "__";
    String indexName;
    Integer shardNumber;
    Integer startingDocId;

    public WorkItem(String indexName, Integer shardNumber, Integer startingDocId) {
        if (indexName.contains(SEPARATOR)) {
            throw new IllegalArgumentException(
                "Illegal work item name: '" + indexName + "'.  " + "Work item names cannot contain '" + SEPARATOR + "'"
            );
        }
        this.indexName = indexName;
        this.shardNumber = shardNumber;
        this.startingDocId = startingDocId;
    }

    @Override
    public String toString() {
        var name = indexName;
        if (shardNumber != null) {
            name += SEPARATOR + shardNumber;
        }
        if (startingDocId != null) {
            name += SEPARATOR + startingDocId;
        }
        return name;
    }

    public static WorkItem fromString(String input) {
        if ("shard_setup".equals(input)) {
            return new WorkItem(input, null, null);
        }
        var components = input.split(SEPARATOR + "+");
        if (components.length != 3) {
            throw new IllegalArgumentException("Illegal work item: '" + input + "'");
        }
        return new WorkItem(components[0], Integer.parseInt(components[1]), Integer.parseInt(components[2]));
    }
}
