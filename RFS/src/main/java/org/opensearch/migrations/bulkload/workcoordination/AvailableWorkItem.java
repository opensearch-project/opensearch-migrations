package org.opensearch.migrations.bulkload.workcoordination;

import lombok.Value;

@Value
class AvailableWorkItem {
    String workItemId;
    int nextAcquisitionLeaseExponent;
    String successorItems;
}
