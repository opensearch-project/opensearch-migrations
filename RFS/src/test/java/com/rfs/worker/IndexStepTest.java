package com.rfs.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rfs.cms.CmsClient;
import com.rfs.cms.CmsEntry;
import com.rfs.cms.OpenSearchCmsClient;
import com.rfs.common.SnapshotRepo;
import com.rfs.models.IndexMetadata;
import com.rfs.transformers.Transformer;
import com.rfs.version_os_2_11.IndexCreator_OS_2_11;
import com.rfs.worker.IndexStep.SharedMembers;
import com.rfs.worker.IndexStep.MaxAttemptsExceeded;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class IndexStepTest {
    private SharedMembers testMembers;

    @BeforeEach
    void setUp() {
        GlobalState globalState = Mockito.mock(GlobalState.class);
        CmsClient cmsClient = Mockito.mock(CmsClient.class);
        String snapshotName = "test";

        IndexMetadata.Factory metadataFactory = Mockito.mock(IndexMetadata.Factory.class);
        IndexCreator_OS_2_11 indexCreator = Mockito.mock(IndexCreator_OS_2_11.class);
        Transformer transformer = Mockito.mock(Transformer.class);
        testMembers = new SharedMembers(globalState, cmsClient, snapshotName, metadataFactory, indexCreator, transformer);        
    }

    @Test
    void EnterPhase_AsExpected() {
        // Run the test
        IndexStep.EnterPhase testStep = new IndexStep.EnterPhase(testMembers);
        testStep.run();
        WorkerStep nextStep = testStep.nextStep();

        // Check the results
        Mockito.verify(testMembers.globalState, times(1)).updatePhase(GlobalState.Phase.INDEX_IN_PROGRESS);
        assertEquals(IndexStep.GetEntry.class, nextStep.getClass());
    }

    static Stream<Arguments> provideGetEntryArgs() {
        return Stream.of(
            // There is no CMS entry, so we need to create one
            Arguments.of(
                Optional.empty(),
                IndexStep.CreateEntry.class
            ),

            // The CMS entry has an expired lease and is under the retry limit, so we try to acquire the lease
            Arguments.of(
                Optional.of(new CmsEntry.Index(
                    CmsEntry.IndexStatus.SETUP,
                    String.valueOf(Instant.now().minus(Duration.ofDays(1)).toEpochMilli()),
                    CmsEntry.Index.MAX_ATTEMPTS - 1
                )),
                IndexStep.AcquireLease.class
            ),

            // The CMS entry has an expired lease and is at the retry limit, so we exit as failed
            Arguments.of(
                Optional.of(new CmsEntry.Index(
                    CmsEntry.IndexStatus.SETUP,
                    String.valueOf(Instant.now().minus(Duration.ofDays(1)).toEpochMilli()),
                    CmsEntry.Index.MAX_ATTEMPTS
                )),
                IndexStep.ExitPhaseFailed.class
            ),

            // The CMS entry has an expired lease and is over the retry limit, so we exit as failed
            Arguments.of(
                Optional.of(new CmsEntry.Index(
                    CmsEntry.IndexStatus.SETUP,
                    String.valueOf(Instant.now().minus(Duration.ofDays(1)).toEpochMilli()),
                    CmsEntry.Index.MAX_ATTEMPTS + 1
                )),
                IndexStep.ExitPhaseFailed.class
            ),

            // The CMS entry has valid lease and is under the retry limit, so we back off a bit
            Arguments.of(
                Optional.of(new CmsEntry.Index(
                    CmsEntry.IndexStatus.SETUP,
                    String.valueOf(Instant.now().plus(Duration.ofDays(1)).toEpochMilli()),
                    CmsEntry.Index.MAX_ATTEMPTS - 1
                )),
                IndexStep.RandomWait.class
            ),

            // The CMS entry has valid lease and is at the retry limit, so we back off a bit
            Arguments.of(
                Optional.of(new CmsEntry.Index(
                    CmsEntry.IndexStatus.SETUP,
                    String.valueOf(Instant.now().plus(Duration.ofDays(1)).toEpochMilli()),
                    CmsEntry.Index.MAX_ATTEMPTS
                )),
                IndexStep.RandomWait.class
            ),

            // The CMS entry is marked as in progress, so we try to do some work
            Arguments.of(
                Optional.of(new CmsEntry.Index(
                    CmsEntry.IndexStatus.IN_PROGRESS,
                    String.valueOf(Instant.now().plus(Duration.ofDays(1)).toEpochMilli()),
                    CmsEntry.Index.MAX_ATTEMPTS - 1
                )),
                IndexStep.GetIndicesToMigrate.class
            ),

            // The CMS entry is marked as completed, so we exit as success
            Arguments.of(
                Optional.of(new CmsEntry.Index(
                    CmsEntry.IndexStatus.COMPLETED,
                    String.valueOf(Instant.now().plus(Duration.ofDays(1)).toEpochMilli()),
                    CmsEntry.Index.MAX_ATTEMPTS - 1
                )),
                IndexStep.ExitPhaseSuccess.class
            ),

            // The CMS entry is marked as failed, so we exit as failed
            Arguments.of(
                Optional.of(new CmsEntry.Index(
                    CmsEntry.IndexStatus.FAILED,
                    String.valueOf(Instant.now().plus(Duration.ofDays(1)).toEpochMilli()),
                    CmsEntry.Index.MAX_ATTEMPTS - 1
                )),
                IndexStep.ExitPhaseFailed.class
            )
        );
    }

    @ParameterizedTest
    @MethodSource("provideGetEntryArgs")
    void GetEntry_AsExpected(Optional<CmsEntry.Index> index, Class<?> nextStepClass) {
        // Set up the test
        Mockito.when(testMembers.cmsClient.getIndexEntry()).thenReturn(index);

        // Run the test
        IndexStep.GetEntry testStep = new IndexStep.GetEntry(testMembers);
        testStep.run();
        WorkerStep nextStep = testStep.nextStep();

        // Check the results
        Mockito.verify(testMembers.cmsClient, times(1)).getIndexEntry();
        assertEquals(nextStepClass, nextStep.getClass());
    }

    static Stream<Arguments> provideCreateEntryArgs() {
        return Stream.of(
            // We were able to create the CMS entry ourselves, so we have the work lease
            Arguments.of(                
                Optional.of(new CmsEntry.Index(
                    CmsEntry.IndexStatus.IN_PROGRESS,
                    String.valueOf(Instant.now().plus(Duration.ofDays(1)).toEpochMilli()),
                    1
                )),
                IndexStep.SetupIndexWorkEntries.class
            ),

            // We were unable to create the CMS entry ourselves, so we do not have the work lease
            Arguments.of(Optional.empty(), IndexStep.GetEntry.class)
        );
    }

    @ParameterizedTest
    @MethodSource("provideCreateEntryArgs")
    void CreateEntry_AsExpected(Optional<CmsEntry.Index> createdEntry, Class<?> nextStepClass) {
        // Set up the test
        Mockito.when(testMembers.cmsClient.createIndexEntry()).thenReturn(createdEntry);

        // Run the test
        IndexStep.CreateEntry testStep = new IndexStep.CreateEntry(testMembers);
        testStep.run();
        WorkerStep nextStep = testStep.nextStep();

        // Check the results
        Mockito.verify(testMembers.cmsClient, times(1)).createIndexEntry();
        assertEquals(nextStepClass, nextStep.getClass());
    }

    public static class TestAcquireLease extends IndexStep.AcquireLease {
        public static final int milliSinceEpoch = 42; // Arbitrarily chosen, but predictable

        public TestAcquireLease(SharedMembers members) {
            super(members);
        }

        @Override
        protected long getNowMs() {
            return milliSinceEpoch;
        }
    }

    static Stream<Arguments> provideAcquireLeaseArgs() {
        return Stream.of(
            // We were able to acquire the lease
            Arguments.of(
                Optional.of(new CmsEntry.Index(
                    CmsEntry.IndexStatus.SETUP,
                    String.valueOf(Instant.now().plus(Duration.ofDays(1)).toEpochMilli()),
                    1
                )),
                IndexStep.SetupIndexWorkEntries.class
            ),

            // We were unable to acquire the lease
            Arguments.of(Optional.empty(), IndexStep.RandomWait.class)
        );
    }

    @ParameterizedTest
    @MethodSource("provideAcquireLeaseArgs")
    void AcquireLease_AsExpected(Optional<CmsEntry.Index> updatedEntry, Class<?> nextStepClass) {
        // Set up the test
        var existingEntry = Optional.of(new CmsEntry.Index(
            CmsEntry.IndexStatus.SETUP,
            CmsEntry.Index.getLeaseExpiry(0L, CmsEntry.Index.MAX_ATTEMPTS - 1),
            CmsEntry.Index.MAX_ATTEMPTS - 1
        ));
        testMembers.cmsEntry = existingEntry;

        Mockito.when(testMembers.cmsClient.updateIndexEntry(
            any(CmsEntry.Index.class), eq(existingEntry.get())
        )).thenReturn(updatedEntry);

        // Run the test
        IndexStep.AcquireLease testStep = new TestAcquireLease(testMembers);
        testStep.run();
        WorkerStep nextStep = testStep.nextStep();

        // Check the results
        var expectedEntry = new CmsEntry.Index(
            CmsEntry.IndexStatus.SETUP,
            CmsEntry.Index.getLeaseExpiry(TestAcquireLease.milliSinceEpoch, CmsEntry.Index.MAX_ATTEMPTS),
            CmsEntry.Index.MAX_ATTEMPTS
        );
        Mockito.verify(testMembers.cmsClient, times(1)).updateIndexEntry(
            expectedEntry, existingEntry.get()
        );
        assertEquals(nextStepClass, nextStep.getClass());
    }

    static Stream<Arguments> provideSetupIndexWorkEntriesArgs() {
        return Stream.of(
            // We were able to mark the setup as completed
            Arguments.of(                
                Optional.of(new CmsEntry.Index(
                    CmsEntry.IndexStatus.IN_PROGRESS,
                    String.valueOf(42),
                    1
                )),
                IndexStep.GetIndicesToMigrate.class
            ),

            // We were unable to mark the setup as completed
            Arguments.of(Optional.empty(), IndexStep.GetEntry.class)
        );
    }

    @ParameterizedTest
    @MethodSource("provideSetupIndexWorkEntriesArgs")
    void SetupIndexWorkEntries_AsExpected(Optional<CmsEntry.Index> updatedEntry, Class<?> nextStepClass) {
        // Set up the test
        var existingEntry = Optional.of(new CmsEntry.Index(
            CmsEntry.IndexStatus.SETUP,
            String.valueOf(42),
            1
        ));
        testMembers.cmsEntry = existingEntry;

        SnapshotRepo.Provider repoDatProvider = Mockito.mock(SnapshotRepo.Provider.class);
        Mockito.when(testMembers.metadataFactory.getRepoDataProvider()).thenReturn(repoDatProvider);
        
        SnapshotRepo.Index index1 = Mockito.mock(SnapshotRepo.Index.class);
        Mockito.when(index1.getName()).thenReturn("index1");
        SnapshotRepo.Index index2 = Mockito.mock(SnapshotRepo.Index.class);
        Mockito.when(index2.getName()).thenReturn("index2");
        Mockito.when(repoDatProvider.getIndicesInSnapshot(testMembers.snapshotName)).thenReturn(
            Stream.of(index1, index2).collect(Collectors.toList())
        );

        IndexMetadata.Data indexMetadata1 = Mockito.mock(IndexMetadata.Data.class);
        Mockito.when(indexMetadata1.getName()).thenReturn("index1");
        Mockito.when(indexMetadata1.getNumberOfShards()).thenReturn(1);
        Mockito.when(testMembers.metadataFactory.fromRepo(testMembers.snapshotName, "index1")).thenReturn(indexMetadata1);

        IndexMetadata.Data indexMetadata2 = Mockito.mock(IndexMetadata.Data.class);
        Mockito.when(indexMetadata2.getName()).thenReturn("index2");
        Mockito.when(indexMetadata2.getNumberOfShards()).thenReturn(2);
        Mockito.when(testMembers.metadataFactory.fromRepo(testMembers.snapshotName, "index2")).thenReturn(indexMetadata2);

        CmsEntry.Index expectedEntry = new CmsEntry.Index(
            CmsEntry.IndexStatus.IN_PROGRESS,
            existingEntry.get().leaseExpiry,
            existingEntry.get().numAttempts
        );
        Mockito.when(testMembers.cmsClient.updateIndexEntry(
            expectedEntry, existingEntry.get()
        )).thenReturn(updatedEntry);

        // Run the test
        IndexStep.SetupIndexWorkEntries testStep = new IndexStep.SetupIndexWorkEntries(testMembers);
        testStep.run();
        WorkerStep nextStep = testStep.nextStep();

        // Check the results
        Mockito.verify(testMembers.globalState, times(1)).updateWorkItem(
            argThat(argument -> {
                if (!(argument instanceof OpenSearchWorkItem)) {
                    return false;
                }
                OpenSearchWorkItem workItem = (OpenSearchWorkItem) argument;
                return workItem.indexName.equals(OpenSearchCmsClient.CMS_INDEX_NAME) &&
                    workItem.documentId.equals(OpenSearchCmsClient.CMS_INDEX_DOC_ID);
            })
        );
        Mockito.verify(testMembers.cmsClient, times(1)).createIndexWorkItem(
            "index1", 1       
        );
        Mockito.verify(testMembers.cmsClient, times(1)).createIndexWorkItem(
            "index2", 2       
        );

        Mockito.verify(testMembers.cmsClient, times(1)).updateIndexEntry(
            expectedEntry, existingEntry.get()
        );
        Mockito.verify(testMembers.globalState, times(1)).updateWorkItem(
            null
        );

        assertEquals(nextStepClass, nextStep.getClass());
    }

    static Stream<Arguments> provideGetIndicesToMigrateArgs() {
        return Stream.of(
            // There's still work to do
            Arguments.of(                
                Stream.of(
                    new CmsEntry.IndexWorkItem("index1", CmsEntry.IndexWorkItemStatus.NOT_STARTED, 1, 1),
                    new CmsEntry.IndexWorkItem("index2", CmsEntry.IndexWorkItemStatus.NOT_STARTED, 1, 2)                
                ).collect(Collectors.toList()),
                IndexStep.MigrateIndices.class
            ),

            // There's no more work to do
            Arguments.of(List.of(), IndexStep.ExitPhaseSuccess.class)
        );
    }

    @ParameterizedTest
    @MethodSource("provideGetIndicesToMigrateArgs")
    void GetIndicesToMigrate_AsExpected(List<CmsEntry.IndexWorkItem> workItems, Class<?> nextStepClass) {
        // Set up the test
        Mockito.when(testMembers.cmsClient.getAvailableIndexWorkItems(IndexStep.GetIndicesToMigrate.MAX_WORK_ITEMS)).thenReturn(workItems);

        // Run the test
        IndexStep.GetIndicesToMigrate testStep = new IndexStep.GetIndicesToMigrate(testMembers);
        testStep.run();
        WorkerStep nextStep = testStep.nextStep();

        // Check the results
        assertEquals(nextStepClass, nextStep.getClass());
    }

    static Stream<Arguments> provideMigrateIndicesArgs() {
        return Stream.of(
            // We have an to migrate and we create it
            Arguments.of(
                new CmsEntry.IndexWorkItem("index1", CmsEntry.IndexWorkItemStatus.NOT_STARTED, 1, 1),
                Optional.of(Mockito.mock(ObjectNode.class)),
                new CmsEntry.IndexWorkItem("index1", CmsEntry.IndexWorkItemStatus.COMPLETED, 1, 1)
            ),

            // We have an index to migrate and someone else created it before us
            Arguments.of(
                new CmsEntry.IndexWorkItem("index2", CmsEntry.IndexWorkItemStatus.NOT_STARTED, 1, 2),
                Optional.empty(),
                new CmsEntry.IndexWorkItem("index2", CmsEntry.IndexWorkItemStatus.COMPLETED, 1, 2)
            )
        );
    }

    @ParameterizedTest
    @MethodSource("provideMigrateIndicesArgs")
    void MigrateIndices_workToDo_AsExpected(CmsEntry.IndexWorkItem workItem, Optional<ObjectNode> createResponse, CmsEntry.IndexWorkItem updatedItem) {
        // Set up the test
        IndexMetadata.Data indexMetadata = Mockito.mock(IndexMetadata.Data.class);
        Mockito.when(testMembers.metadataFactory.fromRepo(testMembers.snapshotName, workItem.name)).thenReturn(indexMetadata);

        ObjectNode root = Mockito.mock(ObjectNode.class);
        Mockito.when(indexMetadata.toObjectNode()).thenReturn(root);
        Mockito.when(indexMetadata.getId()).thenReturn("index-id");
        ObjectNode transformedRoot = Mockito.mock(ObjectNode.class);
        Mockito.when(testMembers.transformer.transformIndexMetadata(root)).thenReturn(transformedRoot);
        Mockito.when(testMembers.indexCreator.create(transformedRoot,  workItem.name, "index-id")).thenReturn(createResponse);        

        // Run the test
        IndexStep.MigrateIndices testStep = new IndexStep.MigrateIndices(testMembers, List.of(workItem));
        testStep.run();
        WorkerStep nextStep = testStep.nextStep();

        // Check the results
        Mockito.verify(testMembers.cmsClient, times(1)).updateIndexWorkItemForceful(updatedItem);
        assertEquals(IndexStep.GetIndicesToMigrate.class, nextStep.getClass());
    }

    @Test
    void MigrateIndices_exceededAttempts_AsExpected(){
        // Set up the test
        CmsEntry.IndexWorkItem workItem = new CmsEntry.IndexWorkItem("index1", CmsEntry.IndexWorkItemStatus.NOT_STARTED, CmsEntry.IndexWorkItem.ATTEMPTS_SOFT_LIMIT + 1, 1);
        CmsEntry.IndexWorkItem updatedItem = new CmsEntry.IndexWorkItem("index1", CmsEntry.IndexWorkItemStatus.FAILED, CmsEntry.IndexWorkItem.ATTEMPTS_SOFT_LIMIT + 1, 1);

        // Run the test
        IndexStep.MigrateIndices testStep = new IndexStep.MigrateIndices(testMembers, List.of(workItem));
        testStep.run();
        WorkerStep nextStep = testStep.nextStep();

        // Check the results
        Mockito.verify(testMembers.cmsClient, times(1)).updateIndexWorkItem(updatedItem, workItem);
        assertEquals(IndexStep.GetIndicesToMigrate.class, nextStep.getClass());
    }

    @Test
    void MigrateIndices_workItemFailed_AsExpected(){
        // Set up the test
        CmsEntry.IndexWorkItem workItem = new CmsEntry.IndexWorkItem("index1", CmsEntry.IndexWorkItemStatus.NOT_STARTED, 1, 1);
        CmsEntry.IndexWorkItem updatedItem = new CmsEntry.IndexWorkItem("index1", CmsEntry.IndexWorkItemStatus.NOT_STARTED, 2, 1);
        doThrow(new RuntimeException("Test exception")).when(testMembers.metadataFactory).fromRepo(testMembers.snapshotName, workItem.name);

        // Run the test
        IndexStep.MigrateIndices testStep = new IndexStep.MigrateIndices(testMembers, List.of(workItem));
        testStep.run();
        WorkerStep nextStep = testStep.nextStep();

        // Check the results
        Mockito.verify(testMembers.cmsClient, times(1)).updateIndexWorkItem(updatedItem, workItem);
        assertEquals(IndexStep.GetIndicesToMigrate.class, nextStep.getClass());
    }

    public static class TestRandomWait extends IndexStep.RandomWait {
        public TestRandomWait(SharedMembers members) {
            super(members);
        }

        @Override
        protected void waitABit() {
            // do nothing
        }
    }

    @Test
    void RandomWait_AsExpected() {
        // Run the test
        IndexStep.RandomWait testStep = new TestRandomWait(testMembers);
        testStep.run();
        WorkerStep nextStep = testStep.nextStep();

        // Check the results
        assertEquals(IndexStep.GetEntry.class, nextStep.getClass());
    }

    @Test
    void ExitPhaseSuccess_AsExpected() {
        // Set up the test
        var existingEntry = Optional.of(new CmsEntry.Index(
            CmsEntry.IndexStatus.IN_PROGRESS,
            String.valueOf(42),
            1
        ));
        testMembers.cmsEntry = existingEntry;

        // Run the test
        IndexStep.ExitPhaseSuccess testStep = new IndexStep.ExitPhaseSuccess(testMembers);
        testStep.run();
        WorkerStep nextStep = testStep.nextStep();

        // Check the results
        var expectedEntry = new CmsEntry.Index(
            CmsEntry.IndexStatus.COMPLETED,
            existingEntry.get().leaseExpiry,
            existingEntry.get().numAttempts
        );
        Mockito.verify(testMembers.cmsClient, times(1)).updateIndexEntry(
            expectedEntry, existingEntry.get()
        );

        Mockito.verify(testMembers.globalState, times(1)).updatePhase(
            GlobalState.Phase.INDEX_COMPLETED
        );
        assertEquals(null, nextStep);
    }

    @Test
    void ExitPhaseFailed_AsExpected() {
        // Set up the test
        MaxAttemptsExceeded e = new MaxAttemptsExceeded();

        var existingEntry = Optional.of(new CmsEntry.Index(
            CmsEntry.IndexStatus.SETUP,
            String.valueOf(42),
            1
        ));
        testMembers.cmsEntry = existingEntry;

        // Run the test
        IndexStep.ExitPhaseFailed testStep = new IndexStep.ExitPhaseFailed(testMembers, e);
        testStep.run();
        assertThrows(MaxAttemptsExceeded.class, () -> {
            testStep.nextStep();
        });

        // Check the results
        var expectedEntry = new CmsEntry.Index(
            CmsEntry.IndexStatus.FAILED,
            existingEntry.get().leaseExpiry,
            existingEntry.get().numAttempts
        );
        Mockito.verify(testMembers.cmsClient, times(1)).updateIndexEntry(
            expectedEntry, existingEntry.get()
        );
        Mockito.verify(testMembers.globalState, times(1)).updatePhase(
            GlobalState.Phase.INDEX_FAILED
        );
    }
    
}
