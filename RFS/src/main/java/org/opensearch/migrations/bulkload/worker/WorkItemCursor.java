package org.opensearch.migrations.bulkload.worker;

import lombok.Value;

@Value
public class WorkItemCursor {
    long progressCheckpointNum;
}
