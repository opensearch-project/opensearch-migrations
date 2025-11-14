package org.opensearch.migrations.bulkload.workcoordination;

import lombok.Value;

@Value
class LeaseResult {
    Long completedAt;
    long expiration;
    String leaseHolderId;
}
