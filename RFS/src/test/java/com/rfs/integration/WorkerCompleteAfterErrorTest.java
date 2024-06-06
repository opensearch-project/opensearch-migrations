package com.rfs.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import lombok.extern.slf4j.Slf4j;

/**
 * Test case to verify worker will not deadlock and complete all work given failures
 * during critical migration workflows.
 */
@Slf4j
public class WorkerCompleteAfterErrorTest {


    @ParameterizedTest(name = "Injected failure on {0} step")
    @EnumSource(InjectedFailure.class)
    public void SingleWorkerFailure(final InjectedFailure failure){
        // Setup
        setupCms();
        setupMockSource();
        setupMockTarget();

        // PSUEDO: Create a 5 workers
        // PSUEDO: Wrap worker[0] with failure injector
        // PSUEDO: Register worker[0] to fault on 'failure' parameter

        // Action
        // PSUEDO: Start all workers
        // PSUEDO: Wait until all stop

        // Verify
        // PSUEDO: Verify 4 original workers finished without error
        // PSUEDO: Verify 1 failure was injected
        verifyTargetMigrated();
    }

    @ParameterizedTest(name = "Injected failure on {0} step")
    @EnumSource(InjectedFailure.class)
    public void ConcurrentAllWorkerFailure(){
        // Setup
        setupCms();
        setupMockSource();
        setupMockTarget();

        // PSUEDO: Create a 5 workers
        // PSUEDO: Wrap all workers with failure injector
        // PSUEDO: Register all workers to fault on 'failure' parameter

        // Action
        // PSUEDO: Start all workers
        // PSUEDO: Wait until all stop
        // PSUEDO: Start 5 fresh workers

        // Verification
        // PSUEDO: Verify failure injector was called on all workers
        // PSUEDO: Verify all new workers completed without issue
        verifyTargetMigrated();
    }

    @Test
    public void DisjointedAllWorkerFailure(){
        // Setup
        setupCms();
        setupMockSource();
        setupMockTarget();

        final var workerCount = InjectedFailure.values().length + 1;
        // PSUEDO: Create a workerCount workers
        // PSUEDO: Wrap all workers with failure injector
        // PSUEDO: Map InjectedFailure.values() onto workers of the same index

        // Action
        // PSUEDO: Start all workers
        // PSUEDO: Wait until all stop

        // Verification
        // PSUEDO: Verify failure injector was called one once on each type of InjectedFailure
        // PSUEDO: Verify last worker completed without issue
        verifyTargetMigrated();
    }

    private void setupCms() {
        // PSUEDO: Setup mock cms store
    }

    private void setupMockSource() {
        // PSUEDO: Setup mock source cluster
        //   - Mock snapshot workflow
        //   - Mock index templates
        //   - Mock indices
        //   - Mock documents
    }

    private void setupMockTarget() {
        // PSUEDO: Setup mock migration target
        //   - Mock OpenSearch client
    }

    private void verifyTargetMigrated() {
        // PSUEDO: Verify migration target
        //   - Verify all index templates attempted at least once
        //   - Verify all indices attempted at least once
        //   - Verify all documents attempted at least once
        // E.g. verify(targetClient, atLeastOnce()).createIndexTemplate("{index-template-name}", any())
    }

    private static enum InjectedFailure {
        SNAPSHOT_INITATE,
        SNAPSHOT_WAIT,
        TEMPLATES_MIGRATE,
        INDEX_RETRIEVE,
        INDEX_MIGRATE,
        DOCS_READING_SNAPSHOT, // Assuming this is forthcoming
        DOCS_MIGRATE; // Assuming this is forthcoming
    }
}
