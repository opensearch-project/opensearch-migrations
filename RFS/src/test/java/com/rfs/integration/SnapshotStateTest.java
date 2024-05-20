package com.rfs.integration;

public class SnapshotStateTest {

    public void SingleSnapshot_SingleDocument() {
        // Setup
        // PSUEDO: Create an 1 index with 1 document
        // PSUEDO: Save snapshot1
        // PSUEDO: Start RFS worker reader, point to snapshot1
        // PSUEDO: Attach sink to inspect all of the operations performed on the target cluster

        // Action
        // PSUEDO: Start reindex on the worker
        // PSUEDO: Wait until the operations sink has settled with expected operations. 

        // Validation
        // PSUEDO: Read the actions from the sink
        // PSUEDO: Flush all read-only operations from the sink, such as refresh, searchs, etc...
        // PSUEDO: Scan the sink for ONLY the following:
        //    PSUEDO: Should see create index
        //    PSUEDO: Should see bulk put document, with single document
        //    PSUEDO: Should see more than one refresh index calls (other misc expected write operations)

        // PSUEDO: Verify no other items were present in the sync
    }

    public void MultiSnapshot_SingleDocument_Then_DeletedDocument() {
        // Setup
        // PSUEDO: Create an 1 index with 1 document
        // PSUEDO: Save snapshot1
        // PSUEDO: Delete the document
        // PSUEDO: Save snapshot2
        // PSUEDO: Start RFS worker reader, point to the snapshot2
        // PSUEDO: Attach sink to inspect all of the operations performed on the target cluster

        // Action
        // PSUEDO: Start reindex on the worker
        // PSUEDO: Wait until the operations sink has settled with expected operations. 

        // Validation
        // PSUEDO: Read the actions from the sink
        // PSUEDO: Flush all read-only operations from the sink, such as refresh, searchs, etc...
        // PSUEDO: Scan the sink for ONLY the following:
        //    PSUEDO: Should see create index
        //    PSUEDO: Should see more than one refresh index calls (other misc expected write operations)

        // PSUEDO: Verify no other items were present in the sync
    }

    public void MultiSnapshot_SingleDocument_Then_UpdateDocument() {
        // Setup
        // PSUEDO: Create an 1 index with 1 document
        // PSUEDO: Save snapshot1
        // PSUEDO: Update the document
        // PSUEDO: Save snapshot2
        // PSUEDO: Start RFS worker reader, point to the snapshot2
        // PSUEDO: Attach sink to inspect all of the operations performed on the target cluster

        // Action
        // PSUEDO: Start reindex on the worker
        // PSUEDO: Wait until the operations sink has settled with expected operations. 

        // Validation
        // PSUEDO: Read the actions from the sink
        // PSUEDO: Flush all read-only operations from the sink, such as refresh, searchs, etc...
        // PSUEDO: Scan the sink for ONLY the following:
        //    PSUEDO: Should see create index
        //    PSUEDO: Should see bulk put document, with single document which is updated version
        //    PSUEDO: Should see more than one refresh index calls (other misc expected write operations)

        // PSUEDO: Verify no other items were present in the sync
    }
}
