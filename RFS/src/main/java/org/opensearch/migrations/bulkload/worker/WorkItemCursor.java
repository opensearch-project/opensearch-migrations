package org.opensearch.migrations.bulkload.worker;

import lombok.Value;

/**
 * The last position a worker has durably committed for its work item.
 *
 * <p>The cursor is opaque: it comes from the source that produced the documents and is only ever
 * handed back to that source, or written into a successor work item.
 */
@Value
public class WorkItemCursor {
    String cursor;
}
