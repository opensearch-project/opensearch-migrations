package com.rfs.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.apache.lucene.document.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import com.rfs.cms.CmsClient;
import com.rfs.cms.CmsEntry;
import com.rfs.cms.OpenSearchCmsClient;
import com.rfs.common.DocumentReindexer;
import com.rfs.common.IndexMetadata;
import com.rfs.common.LuceneDocumentsReader;
import com.rfs.common.ShardMetadata;
import com.rfs.common.SnapshotRepo;
import com.rfs.common.SnapshotShardUnpacker;
import com.rfs.worker.DocumentsStep.SharedMembers;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import com.rfs.worker.DocumentsStep.MaxAttemptsExceeded;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;


@ExtendWith(MockitoExtension.class)
public class DocumentsStepTest {
    private SharedMembers testMembers;
    private SnapshotShardUnpacker unpacker;

    @BeforeEach
    void setUp() {
        GlobalState globalState = Mockito.mock(GlobalState.class);
        CmsClient cmsClient = Mockito.mock(CmsClient.class);
        String snapshotName = "test";
        long maxShardSizeBytes = 50 * 1024 * 1024 * 1024L;

        IndexMetadata.Factory metadataFactory = Mockito.mock(IndexMetadata.Factory.class);
        ShardMetadata.Factory shardMetadataFactory = Mockito.mock(ShardMetadata.Factory.class);

        unpacker = Mockito.mock(SnapshotShardUnpacker.class);
        SnapshotShardUnpacker.Factory unpackerFactory = Mockito.mock(SnapshotShardUnpacker.Factory.class);
        lenient().when(unpackerFactory.create(any())).thenReturn(unpacker);

        LuceneDocumentsReader reader = Mockito.mock(LuceneDocumentsReader.class);
        DocumentReindexer reindexer = Mockito.mock(DocumentReindexer.class);
        testMembers = new SharedMembers(globalState, cmsClient, snapshotName, maxShardSizeBytes, metadataFactory, shardMetadataFactory, unpackerFactory, reader, reindexer);
    }

    @Test
    void EnterPhase_AsExpected() {
        // Run the test
        DocumentsStep.EnterPhase testStep = new DocumentsStep.EnterPhase(testMembers);
        testStep.run();
        WorkerStep nextStep = testStep.nextStep();

        // Check the results
        Mockito.verify(testMembers.globalState, times(1)).updatePhase(GlobalState.Phase.DOCUMENTS_IN_PROGRESS);
        assertEquals(DocumentsStep.GetEntry.class, nextStep.getClass());
    }

    static Stream<Arguments> provideGetEntryArgs() {
        return Stream.of(
            // There is no CMS entry, so we need to create one
            Arguments.of(
                Optional.empty(),
                DocumentsStep.CreateEntry.class
            ),

            // The CMS entry has an expired lease and is under the retry limit, so we try to acquire the lease
            Arguments.of(
                Optional.of(new CmsEntry.Documents(
                    CmsEntry.DocumentsStatus.SETUP,
                    String.valueOf(Instant.now().minus(Duration.ofDays(1)).toEpochMilli()),
                    CmsEntry.Documents.MAX_ATTEMPTS - 1
                )),
                DocumentsStep.AcquireLease.class
            ),

            // The CMS entry has an expired lease and is at the retry limit, so we exit as failed
            Arguments.of(
                Optional.of(new CmsEntry.Documents(
                    CmsEntry.DocumentsStatus.SETUP,
                    String.valueOf(Instant.now().minus(Duration.ofDays(1)).toEpochMilli()),
                    CmsEntry.Documents.MAX_ATTEMPTS
                )),
                DocumentsStep.ExitPhaseFailed.class
            ),

            // The CMS entry has an expired lease and is over the retry limit, so we exit as failed
            Arguments.of(
                Optional.of(new CmsEntry.Documents(
                    CmsEntry.DocumentsStatus.SETUP,
                    String.valueOf(Instant.now().minus(Duration.ofDays(1)).toEpochMilli()),
                    CmsEntry.Documents.MAX_ATTEMPTS + 1
                )),
                DocumentsStep.ExitPhaseFailed.class
            ),

            // The CMS entry has valid lease and is under the retry limit, so we back off a bit
            Arguments.of(
                Optional.of(new CmsEntry.Documents(
                    CmsEntry.DocumentsStatus.SETUP,
                    String.valueOf(Instant.now().plus(Duration.ofDays(1)).toEpochMilli()),
                    CmsEntry.Documents.MAX_ATTEMPTS - 1
                )),
                DocumentsStep.RandomWait.class
            ),

            // The CMS entry has valid lease and is at the retry limit, so we back off a bit
            Arguments.of(
                Optional.of(new CmsEntry.Documents(
                    CmsEntry.DocumentsStatus.SETUP,
                    String.valueOf(Instant.now().plus(Duration.ofDays(1)).toEpochMilli()),
                    CmsEntry.Documents.MAX_ATTEMPTS
                )),
                DocumentsStep.RandomWait.class
            ),

            // The CMS entry is marked as in progress, so we try to do some work
            Arguments.of(
                Optional.of(new CmsEntry.Documents(
                    CmsEntry.DocumentsStatus.IN_PROGRESS,
                    String.valueOf(Instant.now().plus(Duration.ofDays(1)).toEpochMilli()),
                    CmsEntry.Documents.MAX_ATTEMPTS - 1
                )),
                DocumentsStep.GetDocumentsToMigrate.class
            ),

            // The CMS entry is marked as completed, so we exit as success
            Arguments.of(
                Optional.of(new CmsEntry.Documents(
                    CmsEntry.DocumentsStatus.COMPLETED,
                    String.valueOf(Instant.now().plus(Duration.ofDays(1)).toEpochMilli()),
                    CmsEntry.Documents.MAX_ATTEMPTS - 1
                )),
                DocumentsStep.ExitPhaseSuccess.class
            ),

            // The CMS entry is marked as failed, so we exit as failed
            Arguments.of(
                Optional.of(new CmsEntry.Documents(
                    CmsEntry.DocumentsStatus.FAILED,
                    String.valueOf(Instant.now().plus(Duration.ofDays(1)).toEpochMilli()),
                    CmsEntry.Documents.MAX_ATTEMPTS - 1
                )),
                DocumentsStep.ExitPhaseFailed.class
            )
        );
    }

    @ParameterizedTest
    @MethodSource("provideGetEntryArgs")
    void GetEntry_AsExpected(Optional<CmsEntry.Documents> index, Class<?> nextStepClass) {
        // Set up the test
        Mockito.when(testMembers.cmsClient.getDocumentsEntry()).thenReturn(index);

        // Run the test
        DocumentsStep.GetEntry testStep = new DocumentsStep.GetEntry(testMembers);
        testStep.run();
        WorkerStep nextStep = testStep.nextStep();

        // Check the results
        Mockito.verify(testMembers.cmsClient, times(1)).getDocumentsEntry();
        assertEquals(nextStepClass, nextStep.getClass());
    }

    static Stream<Arguments> provideCreateEntryArgs() {
        return Stream.of(
            // We were able to create the CMS entry ourselves, so we have the work lease
            Arguments.of(                
                Optional.of(new CmsEntry.Documents(
                    CmsEntry.DocumentsStatus.IN_PROGRESS,
                    String.valueOf(Instant.now().plus(Duration.ofDays(1)).toEpochMilli()),
                    1
                )),
                DocumentsStep.SetupDocumentsWorkEntries.class
            ),

            // We were unable to create the CMS entry ourselves, so we do not have the work lease
            Arguments.of(Optional.empty(), DocumentsStep.GetEntry.class)
        );
    }

    @ParameterizedTest
    @MethodSource("provideCreateEntryArgs")
    void CreateEntry_AsExpected(Optional<CmsEntry.Documents> createdEntry, Class<?> nextStepClass) {
        // Set up the test
        Mockito.when(testMembers.cmsClient.createDocumentsEntry()).thenReturn(createdEntry);

        // Run the test
        DocumentsStep.CreateEntry testStep = new DocumentsStep.CreateEntry(testMembers);
        testStep.run();
        WorkerStep nextStep = testStep.nextStep();

        // Check the results
        Mockito.verify(testMembers.cmsClient, times(1)).createDocumentsEntry();
        assertEquals(nextStepClass, nextStep.getClass());
    }

    public static class TestAcquireLease extends DocumentsStep.AcquireLease {
        public static final int MILLI_SINCE_EPOCH = 42; // Arbitrarily chosen, but predictable

        public TestAcquireLease(SharedMembers members, CmsEntry.Base entry) {
            super(members, entry);
        }

        @Override
        protected long getNowMs() {
            return MILLI_SINCE_EPOCH;
        }
    }

    static Stream<Arguments> provideAcquireLeaseSetupArgs() {
        return Stream.of(
            // We were able to acquire the lease, and it's on the Document setup step
            Arguments.of(
                Optional.of(new CmsEntry.Documents(  
                    CmsEntry.DocumentsStatus.SETUP,
                    String.valueOf(0L),
                    1
                )),
                Optional.of(new CmsEntry.Documents(
                    CmsEntry.DocumentsStatus.SETUP,
                    CmsEntry.Documents.getLeaseExpiry(TestAcquireLease.MILLI_SINCE_EPOCH, 2),
                    2
                )),
                Optional.of(new CmsEntry.Documents(
                    CmsEntry.DocumentsStatus.SETUP,
                    CmsEntry.Documents.getLeaseExpiry(TestAcquireLease.MILLI_SINCE_EPOCH, 2),
                    2
                )),
                DocumentsStep.SetupDocumentsWorkEntries.class
            ),

            // We were unable to acquire the lease
            Arguments.of(                               
                Optional.of(new CmsEntry.Documents(
                    CmsEntry.DocumentsStatus.SETUP,
                    String.valueOf(0L),
                    1
                )),
                Optional.of(new CmsEntry.Documents(
                    CmsEntry.DocumentsStatus.SETUP,
                    CmsEntry.Documents.getLeaseExpiry(TestAcquireLease.MILLI_SINCE_EPOCH, 2),
                    2
                )),
                Optional.empty(),
                DocumentsStep.RandomWait.class
            )
        );
    }

    @ParameterizedTest
    @MethodSource("provideAcquireLeaseSetupArgs")
    void AcquireLease_Setup_AsExpected(
            Optional<CmsEntry.Documents> existingEntry, // The entry we started with, before trying to acquire the lease
            Optional<CmsEntry.Documents> updatedEntry, // The entry we try to update to
            Optional<CmsEntry.Documents> responseEntry, // The response from the CMS client
            Class<?> nextStepClass) {
        // Set up the test
        testMembers.cmsEntry = existingEntry;

        Mockito.when(testMembers.cmsClient.updateDocumentsEntry(
            any(CmsEntry.Documents.class), eq(existingEntry.get())
        )).thenReturn(responseEntry);

        // Run the test
        DocumentsStep.AcquireLease testStep = new TestAcquireLease(testMembers, existingEntry.get());
        testStep.run();
        WorkerStep nextStep = testStep.nextStep();

        // Check the results
        Mockito.verify(testMembers.cmsClient, times(1)).updateDocumentsEntry(
            updatedEntry.get(), existingEntry.get()
        );
        assertEquals(nextStepClass, nextStep.getClass());
    }

    static Stream<Arguments> provideAcquireLeaseWorkArgs() {
        return Stream.of(
            // We were able to acquire the lease, and it's on a Document Work Item setup
            Arguments.of(
                Optional.of(new CmsEntry.DocumentsWorkItem(
                    "index-name",
                    1,
                    CmsEntry.DocumentsWorkItemStatus.NOT_STARTED,
                    String.valueOf(0L),
                    1
                )),
                Optional.of(new CmsEntry.DocumentsWorkItem(
                    "index-name",
                    1,
                    CmsEntry.DocumentsWorkItemStatus.NOT_STARTED,
                    CmsEntry.DocumentsWorkItem.getLeaseExpiry(TestAcquireLease.MILLI_SINCE_EPOCH, 2),
                    2
                )),
                Optional.of(new CmsEntry.DocumentsWorkItem(
                    "index-name",
                    1,
                    CmsEntry.DocumentsWorkItemStatus.NOT_STARTED,
                    CmsEntry.DocumentsWorkItem.getLeaseExpiry(TestAcquireLease.MILLI_SINCE_EPOCH, 2),
                    2
                )),
                DocumentsStep.MigrateDocuments.class
            ),

            // We were unable to acquire the lease
            Arguments.of(
                Optional.of(new CmsEntry.DocumentsWorkItem(
                    "index-name",
                    1,
                    CmsEntry.DocumentsWorkItemStatus.NOT_STARTED,
                    String.valueOf(0L),
                    1
                )),
                Optional.of(new CmsEntry.DocumentsWorkItem(
                    "index-name",
                    1,
                    CmsEntry.DocumentsWorkItemStatus.NOT_STARTED,
                    CmsEntry.DocumentsWorkItem.getLeaseExpiry(TestAcquireLease.MILLI_SINCE_EPOCH, 2),
                    2
                )),
                Optional.empty(),
                DocumentsStep.RandomWait.class
            )
        );
    }

    @ParameterizedTest
    @MethodSource("provideAcquireLeaseWorkArgs")
    void AcquireLease_Work_AsExpected(
            Optional<CmsEntry.DocumentsWorkItem> existingEntry, // The entry we started with, before trying to acquire the lease
            Optional<CmsEntry.DocumentsWorkItem> updatedEntry, // The entry we try to update to
            Optional<CmsEntry.DocumentsWorkItem> responseEntry, // The response from the CMS client
            Class<?> nextStepClass) {
        // Set up the test
        testMembers.cmsWorkEntry = existingEntry;

        Mockito.when(testMembers.cmsClient.updateDocumentsWorkItem(
            any(CmsEntry.DocumentsWorkItem.class), eq(existingEntry.get())
        )).thenReturn(responseEntry);

        // Run the test
        DocumentsStep.AcquireLease testStep = new TestAcquireLease(testMembers, existingEntry.get());
        testStep.run();
        WorkerStep nextStep = testStep.nextStep();

        // Check the results
        Mockito.verify(testMembers.cmsClient, times(1)).updateDocumentsWorkItem(
            updatedEntry.get(), existingEntry.get()
        );
        assertEquals(nextStepClass, nextStep.getClass());
    }

    static Stream<Arguments> provideSetupDocumentsWorkEntriesArgs() {
        return Stream.of(
            // We were able to mark the setup as completed
            Arguments.of(                
                Optional.of(new CmsEntry.Documents(
                    CmsEntry.DocumentsStatus.IN_PROGRESS,
                    String.valueOf(42),
                    1
                )),
                DocumentsStep.GetDocumentsToMigrate.class
            ),

            // We were unable to mark the setup as completed
            Arguments.of(Optional.empty(), DocumentsStep.GetEntry.class)
        );
    }

    @ParameterizedTest
    @MethodSource("provideSetupDocumentsWorkEntriesArgs")
    void SetupDocumentsWorkEntries_AsExpected(Optional<CmsEntry.Documents> returnedEntry, Class<?> nextStepClass) {
        // Set up the test
        SnapshotRepo.Provider repoDataProvider = Mockito.mock(SnapshotRepo.Provider.class);
        Mockito.when(testMembers.metadataFactory.getRepoDataProvider()).thenReturn(repoDataProvider);

        SnapshotRepo.Index index1 = Mockito.mock(SnapshotRepo.Index.class);
        Mockito.when(index1.getName()).thenReturn("index1");
        SnapshotRepo.Index index2 = Mockito.mock(SnapshotRepo.Index.class);
        Mockito.when(index2.getName()).thenReturn("index2");
        Mockito.when(repoDataProvider.getIndicesInSnapshot(testMembers.snapshotName)).thenReturn(
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

        var existingEntry = Optional.of(new CmsEntry.Documents(
            CmsEntry.DocumentsStatus.SETUP,
            String.valueOf(42),
            1
        ));
        testMembers.cmsEntry = existingEntry;

        CmsEntry.Documents updatedEntry = new CmsEntry.Documents(
            CmsEntry.DocumentsStatus.IN_PROGRESS,
            existingEntry.get().leaseExpiry,
            existingEntry.get().numAttempts
        );
        Mockito.when(testMembers.cmsClient.updateDocumentsEntry(
            updatedEntry, existingEntry.get()
        )).thenReturn(returnedEntry);

        // Run the test
        DocumentsStep.SetupDocumentsWorkEntries testStep = new DocumentsStep.SetupDocumentsWorkEntries(testMembers);
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
                    workItem.documentId.equals(OpenSearchCmsClient.CMS_DOCUMENTS_DOC_ID);
            })
        );
        Mockito.verify(testMembers.cmsClient, times(1)).createDocumentsWorkItem(
            "index1", 0
        );
        Mockito.verify(testMembers.cmsClient, times(1)).createDocumentsWorkItem(
            "index2", 0
        );
        Mockito.verify(testMembers.cmsClient, times(1)).createDocumentsWorkItem(
            "index2", 1
        );

        Mockito.verify(testMembers.cmsClient, times(1)).updateDocumentsEntry(
            updatedEntry, existingEntry.get()
        );
        Mockito.verify(testMembers.globalState, times(1)).updateWorkItem(
            null
        );

        assertEquals(nextStepClass, nextStep.getClass());
    }

    static Stream<Arguments> provideGetDocumentsToMigrateArgs() {
        return Stream.of(
            // There's still work to do
            Arguments.of(
                Optional.of(new CmsEntry.DocumentsWorkItem("index1", 0, CmsEntry.DocumentsWorkItemStatus.NOT_STARTED, "42", 1)),
                DocumentsStep.MigrateDocuments.class
            ),

            // There's no more work to do
            Arguments.of(Optional.empty(), DocumentsStep.ExitPhaseSuccess.class)
        );
    }

    @ParameterizedTest
    @MethodSource("provideGetDocumentsToMigrateArgs")
    void GetDocumentsToMigrate_AsExpected(Optional<CmsEntry.DocumentsWorkItem> workItem, Class<?> nextStepClass) {
        // Set up the test
        Mockito.when(testMembers.cmsClient.getAvailableDocumentsWorkItem()).thenReturn(workItem);

        // Run the test
        DocumentsStep.GetDocumentsToMigrate testStep = new DocumentsStep.GetDocumentsToMigrate(testMembers);
        testStep.run();
        WorkerStep nextStep = testStep.nextStep();

        // Check the results
        assertEquals(nextStepClass, nextStep.getClass());
    }

    static Stream<Arguments> provideMigrateDocumentsArgs() {
        return Stream.of(
            // We have an to migrate and we create it
            Arguments.of(
                new CmsEntry.DocumentsWorkItem("index1", 0, CmsEntry.DocumentsWorkItemStatus.NOT_STARTED, "42", 1),
                new CmsEntry.DocumentsWorkItem("index1", 0, CmsEntry.DocumentsWorkItemStatus.COMPLETED, "42", 1),
                new CmsEntry.DocumentsWorkItem("index1", 0, CmsEntry.DocumentsWorkItemStatus.COMPLETED, "42", 1)
            )
        );
    }

    @ParameterizedTest
    @MethodSource("provideMigrateDocumentsArgs")
    void MigrateDocuments_workToDo_AsExpected(CmsEntry.DocumentsWorkItem workItem, CmsEntry.DocumentsWorkItem updatedItem, CmsEntry.DocumentsWorkItem returnedItem) {
        // Set up the test
        testMembers.cmsWorkEntry = Optional.of(workItem);

        ShardMetadata.Data shardMetadata = Mockito.mock(ShardMetadata.Data.class);
        Mockito.when(shardMetadata.getIndexName()).thenReturn(workItem.indexName);
        Mockito.when(shardMetadata.getShardId()).thenReturn(workItem.shardId);
        Mockito.when(testMembers.shardMetadataFactory.fromRepo(testMembers.snapshotName, workItem.indexName, workItem.shardId)).thenReturn(shardMetadata);

        Flux<Document> documents = Mockito.mock(Flux.class);
        Mockito.when(testMembers.reader.readDocuments(shardMetadata.getIndexName(), shardMetadata.getShardId())).thenReturn(documents);

        Mockito.when(testMembers.reindexer.reindex(workItem.indexName, documents)).thenReturn(Mono.empty());
        
        Mockito.when(testMembers.cmsClient.updateDocumentsWorkItemForceful(updatedItem)).thenReturn(returnedItem);

        // Run the test
        DocumentsStep.MigrateDocuments testStep = new DocumentsStep.MigrateDocuments(testMembers);
        testStep.run();
        WorkerStep nextStep = testStep.nextStep();

        // Check the results
        Mockito.verify(testMembers.reindexer, times(1)).reindex(workItem.indexName, documents);
        Mockito.verify(testMembers.unpackerFactory, times(1)).create(shardMetadata);
        Mockito.verify(unpacker, times(1)).close();
        Mockito.verify(testMembers.cmsClient, times(1)).updateDocumentsWorkItemForceful(updatedItem);
        assertEquals(DocumentsStep.GetDocumentsToMigrate.class, nextStep.getClass());
    }

    @Test
    void MigrateDocuments_failedItem_AsExpected() {
        // Set up the test
        CmsEntry.DocumentsWorkItem workItem = new CmsEntry.DocumentsWorkItem(
            "index1", 0, CmsEntry.DocumentsWorkItemStatus.NOT_STARTED, "42", 1
        );
        testMembers.cmsWorkEntry = Optional.of(workItem);

        CmsEntry.DocumentsWorkItem updatedItem = new CmsEntry.DocumentsWorkItem(
            "index1", 0, CmsEntry.DocumentsWorkItemStatus.NOT_STARTED, "42", 2
        );

        ShardMetadata.Data shardMetadata = Mockito.mock(ShardMetadata.Data.class);
        Mockito.when(shardMetadata.getIndexName()).thenReturn(workItem.indexName);
        Mockito.when(shardMetadata.getShardId()).thenReturn(workItem.shardId);
        Mockito.when(testMembers.shardMetadataFactory.fromRepo(testMembers.snapshotName, workItem.indexName, workItem.shardId)).thenReturn(shardMetadata);

        Flux<Document> documents = Mockito.mock(Flux.class);
        Mockito.when(testMembers.reader.readDocuments(shardMetadata.getIndexName(), shardMetadata.getShardId())).thenReturn(documents);

        Mockito.doThrow(new RuntimeException("Test exception")).when(testMembers.reindexer).reindex(workItem.indexName, documents);        

        // Run the test
        DocumentsStep.MigrateDocuments testStep = new DocumentsStep.MigrateDocuments(testMembers);
        testStep.run();
        WorkerStep nextStep = testStep.nextStep();

        // Check the results
        Mockito.verify(testMembers.reindexer, times(1)).reindex(workItem.indexName, documents);
        Mockito.verify(testMembers.unpackerFactory, times(1)).create(shardMetadata);
        Mockito.verify(unpacker, times(1)).close();
        Mockito.verify(testMembers.cmsClient, times(1)).updateDocumentsWorkItem(updatedItem, workItem);
        assertEquals(DocumentsStep.GetDocumentsToMigrate.class, nextStep.getClass());
    }

    @Test
    void MigrateDocuments_largeShard_AsExpected() {
        // Set up the test
        CmsEntry.DocumentsWorkItem workItem = new CmsEntry.DocumentsWorkItem(
            "index1", 0, CmsEntry.DocumentsWorkItemStatus.NOT_STARTED, "42", 1
        );
        testMembers.cmsWorkEntry = Optional.of(workItem);

        CmsEntry.DocumentsWorkItem updatedItem = new CmsEntry.DocumentsWorkItem(
            "index1", 0, CmsEntry.DocumentsWorkItemStatus.NOT_STARTED, "42", 2
        );

        ShardMetadata.Data shardMetadata = Mockito.mock(ShardMetadata.Data.class);
        Mockito.when(shardMetadata.getTotalSizeBytes()).thenReturn(testMembers.maxShardSizeBytes + 1);
        Mockito.when(testMembers.shardMetadataFactory.fromRepo(testMembers.snapshotName, workItem.indexName, workItem.shardId)).thenReturn(shardMetadata);

        // Run the test
        DocumentsStep.MigrateDocuments testStep = new DocumentsStep.MigrateDocuments(testMembers);
        testStep.run();
        WorkerStep nextStep = testStep.nextStep();

        // Check the results
        Mockito.verify(testMembers.cmsClient, times(1)).updateDocumentsWorkItem(updatedItem, workItem);
        Mockito.verify(testMembers.unpackerFactory, times(0)).create(shardMetadata);
        Mockito.verify(unpacker, times(0)).close();
        assertEquals(DocumentsStep.GetDocumentsToMigrate.class, nextStep.getClass());
    }

    @Test
    void MigrateDocuments_exceededAttempts_AsExpected() {
        // Set up the test
        CmsEntry.DocumentsWorkItem workItem = new CmsEntry.DocumentsWorkItem(
            "index1", 0, CmsEntry.DocumentsWorkItemStatus.NOT_STARTED, "42", CmsEntry.DocumentsWorkItem.MAX_ATTEMPTS + 1
        );
        testMembers.cmsWorkEntry = Optional.of(workItem);

        CmsEntry.DocumentsWorkItem updatedItem = new CmsEntry.DocumentsWorkItem(
            "index1", 0, CmsEntry.DocumentsWorkItemStatus.FAILED, "42", CmsEntry.DocumentsWorkItem.MAX_ATTEMPTS + 1
        );

        // Run the test
        DocumentsStep.MigrateDocuments testStep = new DocumentsStep.MigrateDocuments(testMembers);
        testStep.run();
        WorkerStep nextStep = testStep.nextStep();

        // Check the results
        Mockito.verify(testMembers.cmsClient, times(1)).updateDocumentsWorkItem(updatedItem, workItem);
        assertEquals(DocumentsStep.GetDocumentsToMigrate.class, nextStep.getClass());
    }

    public static class TestRandomWait extends DocumentsStep.RandomWait {
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
        DocumentsStep.RandomWait testStep = new TestRandomWait(testMembers);
        testStep.run();
        WorkerStep nextStep = testStep.nextStep();

        // Check the results
        assertEquals(DocumentsStep.GetEntry.class, nextStep.getClass());
    }

    @Test
    void ExitPhaseSuccess_AsExpected() {
        // Set up the test
        var existingEntry = Optional.of(new CmsEntry.Documents(
            CmsEntry.DocumentsStatus.IN_PROGRESS,
            String.valueOf(42),
            1
        ));
        testMembers.cmsEntry = existingEntry;

        // Run the test
        DocumentsStep.ExitPhaseSuccess testStep = new DocumentsStep.ExitPhaseSuccess(testMembers);
        testStep.run();
        WorkerStep nextStep = testStep.nextStep();

        // Check the results
        var expectedEntry = new CmsEntry.Documents(
            CmsEntry.DocumentsStatus.COMPLETED,
            existingEntry.get().leaseExpiry,
            existingEntry.get().numAttempts
        );
        Mockito.verify(testMembers.cmsClient, times(1)).updateDocumentsEntry(
            expectedEntry, existingEntry.get()
        );

        Mockito.verify(testMembers.globalState, times(1)).updatePhase(
            GlobalState.Phase.DOCUMENTS_COMPLETED
        );
        assertEquals(null, nextStep);
    }

    @Test
    void ExitPhaseFailed_AsExpected() {
        // Set up the test
        MaxAttemptsExceeded e = new MaxAttemptsExceeded();

        var existingEntry = Optional.of(new CmsEntry.Documents(
            CmsEntry.DocumentsStatus.SETUP,
            String.valueOf(42),
            1
        ));
        testMembers.cmsEntry = existingEntry;

        // Run the test
        DocumentsStep.ExitPhaseFailed testStep = new DocumentsStep.ExitPhaseFailed(testMembers, e);
        testStep.run();
        assertThrows(MaxAttemptsExceeded.class, () -> {
            testStep.nextStep();
        });

        // Check the results
        var expectedEntry = new CmsEntry.Documents(
            CmsEntry.DocumentsStatus.FAILED,
            existingEntry.get().leaseExpiry,
            existingEntry.get().numAttempts
        );
        Mockito.verify(testMembers.cmsClient, times(1)).updateDocumentsEntry(
            expectedEntry, existingEntry.get()
        );
        Mockito.verify(testMembers.globalState, times(1)).updatePhase(
            GlobalState.Phase.DOCUMENTS_FAILED
        );
    }
}
