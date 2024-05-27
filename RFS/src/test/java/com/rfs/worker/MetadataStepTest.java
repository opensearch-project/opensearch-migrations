package com.rfs.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
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
import com.rfs.common.GlobalMetadata;
import com.rfs.transformers.Transformer;
import com.rfs.version_os_2_11.GlobalMetadataCreator_OS_2_11;
import com.rfs.worker.MetadataStep.MaxAttemptsExceeded;
import com.rfs.worker.MetadataStep.SharedMembers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;


@ExtendWith(MockitoExtension.class)
public class MetadataStepTest {
    private SharedMembers testMembers;

    @BeforeEach
    void setUp() {
        GlobalState globalState = Mockito.mock(GlobalState.class);
        CmsClient cmsClient = Mockito.mock(CmsClient.class);
        String snapshotName = "test";
        GlobalMetadata.Factory metadataFactory = Mockito.mock(GlobalMetadata.Factory.class);
        GlobalMetadataCreator_OS_2_11 metadataCreator = Mockito.mock(GlobalMetadataCreator_OS_2_11.class);
        Transformer transformer = Mockito.mock(Transformer.class);
        testMembers = new SharedMembers(globalState, cmsClient, snapshotName, metadataFactory, metadataCreator, transformer);        
    }

    @Test
    void EnterPhase_AsExpected() {
        // Run the test
        MetadataStep.EnterPhase testStep = new MetadataStep.EnterPhase(testMembers);
        testStep.run();
        WorkerStep nextStep = testStep.nextStep();

        // Check the results
        Mockito.verify(testMembers.globalState, times(1)).updatePhase(GlobalState.Phase.METADATA_IN_PROGRESS);
        assertEquals(MetadataStep.GetEntry.class, nextStep.getClass());
    }

    static Stream<Arguments> provideGetEntryArgs() {
        return Stream.of(
            // There is no CMS entry, so we need to create one
            Arguments.of(
                Optional.empty(),
                MetadataStep.CreateEntry.class
            ),

            // The CMS entry has an expired lease and is under the retry limit, so we try to acquire the lease
            Arguments.of(
                Optional.of(new CmsEntry.Metadata(
                    CmsEntry.MetadataStatus.IN_PROGRESS,
                    String.valueOf(Instant.now().minus(Duration.ofDays(1)).toEpochMilli()),
                    CmsEntry.Metadata.MAX_ATTEMPTS - 1
                )),
                MetadataStep.AcquireLease.class
            ),

            // The CMS entry has an expired lease and is at the retry limit, so we exit as failed
            Arguments.of(
                Optional.of(new CmsEntry.Metadata(
                    CmsEntry.MetadataStatus.IN_PROGRESS,
                    String.valueOf(Instant.now().minus(Duration.ofDays(1)).toEpochMilli()),
                    CmsEntry.Metadata.MAX_ATTEMPTS
                )),
                MetadataStep.ExitPhaseFailed.class
            ),

            // The CMS entry has an expired lease and is over the retry limit, so we exit as failed
            Arguments.of(
                Optional.of(new CmsEntry.Metadata(
                    CmsEntry.MetadataStatus.IN_PROGRESS,
                    String.valueOf(Instant.now().minus(Duration.ofDays(1)).toEpochMilli()),
                    CmsEntry.Metadata.MAX_ATTEMPTS + 1
                )),
                MetadataStep.ExitPhaseFailed.class
            ),

            // The CMS entry has valid lease and is under the retry limit, so we back off a bit
            Arguments.of(
                Optional.of(new CmsEntry.Metadata(
                    CmsEntry.MetadataStatus.IN_PROGRESS,
                    String.valueOf(Instant.now().plus(Duration.ofDays(1)).toEpochMilli()),
                    CmsEntry.Metadata.MAX_ATTEMPTS - 1
                )),
                MetadataStep.RandomWait.class
            ),

            // The CMS entry has valid lease and is at the retry limit, so we back off a bit
            Arguments.of(
                Optional.of(new CmsEntry.Metadata(
                    CmsEntry.MetadataStatus.IN_PROGRESS,
                    String.valueOf(Instant.now().plus(Duration.ofDays(1)).toEpochMilli()),
                    CmsEntry.Metadata.MAX_ATTEMPTS
                )),
                MetadataStep.RandomWait.class
            ),

            // The CMS entry is marked as completed, so we exit as success
            Arguments.of(
                Optional.of(new CmsEntry.Metadata(
                    CmsEntry.MetadataStatus.COMPLETED,
                    String.valueOf(Instant.now().plus(Duration.ofDays(1)).toEpochMilli()),
                    CmsEntry.Metadata.MAX_ATTEMPTS - 1
                )),
                MetadataStep.ExitPhaseSuccess.class
            ),

            // The CMS entry is marked as completed, so we exit as success
            Arguments.of(
                Optional.of(new CmsEntry.Metadata(
                    CmsEntry.MetadataStatus.FAILED,
                    String.valueOf(Instant.now().plus(Duration.ofDays(1)).toEpochMilli()),
                    CmsEntry.Metadata.MAX_ATTEMPTS - 1
                )),
                MetadataStep.ExitPhaseFailed.class
            )
        );
    }

    @ParameterizedTest
    @MethodSource("provideGetEntryArgs")
    void GetEntry_AsExpected(Optional<CmsEntry.Metadata> metadata, Class<?> nextStepClass) {
        // Set up the test
        Mockito.when(testMembers.cmsClient.getMetadataEntry()).thenReturn(metadata);

        // Run the test
        MetadataStep.GetEntry testStep = new MetadataStep.GetEntry(testMembers);
        testStep.run();
        WorkerStep nextStep = testStep.nextStep();

        // Check the results
        Mockito.verify(testMembers.cmsClient, times(1)).getMetadataEntry();
        assertEquals(nextStepClass, nextStep.getClass());
    }

    static Stream<Arguments> provideCreateEntryArgs() {
        return Stream.of(
            // We were able to create the CMS entry ourselves, so we have the work lease
            Arguments.of(                
                Optional.of(new CmsEntry.Metadata(
                    CmsEntry.MetadataStatus.IN_PROGRESS,
                    String.valueOf(Instant.now().plus(Duration.ofDays(1)).toEpochMilli()),
                    1
                )),
                MetadataStep.MigrateTemplates.class
            ),

            // We were unable to create the CMS entry ourselves, so we do not have the work lease
            Arguments.of(Optional.empty(), MetadataStep.GetEntry.class)
        );
    }

    @ParameterizedTest
    @MethodSource("provideCreateEntryArgs")
    void CreateEntry_AsExpected(Optional<CmsEntry.Metadata> createdEntry, Class<?> nextStepClass) {
        // Set up the test
        Mockito.when(testMembers.cmsClient.createMetadataEntry()).thenReturn(createdEntry);

        // Run the test
        MetadataStep.CreateEntry testStep = new MetadataStep.CreateEntry(testMembers);
        testStep.run();
        WorkerStep nextStep = testStep.nextStep();

        // Check the results
        Mockito.verify(testMembers.cmsClient, times(1)).createMetadataEntry();
        assertEquals(nextStepClass, nextStep.getClass());
    }

    public static class TestAcquireLease extends MetadataStep.AcquireLease {
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
                Optional.of(new CmsEntry.Metadata(
                    CmsEntry.MetadataStatus.IN_PROGRESS,
                    String.valueOf(Instant.now().plus(Duration.ofDays(1)).toEpochMilli()),
                    1
                )),
                MetadataStep.MigrateTemplates.class
            ),

            // We were unable to acquire the lease
            Arguments.of(Optional.empty(), MetadataStep.RandomWait.class)
        );
    }

    @ParameterizedTest
    @MethodSource("provideAcquireLeaseArgs")
    void AcquireLease_AsExpected(Optional<CmsEntry.Metadata> updatedEntry, Class<?> nextStepClass) {
        // Set up the test
        var existingEntry = Optional.of(new CmsEntry.Metadata(
            CmsEntry.MetadataStatus.IN_PROGRESS,
            CmsEntry.Metadata.getLeaseExpiry(0L, CmsEntry.Metadata.MAX_ATTEMPTS - 1),
            CmsEntry.Metadata.MAX_ATTEMPTS - 1
        ));
        testMembers.cmsEntry = existingEntry;

        Mockito.when(testMembers.cmsClient.updateMetadataEntry(
            any(CmsEntry.MetadataStatus.class), anyString(), anyInt()
        )).thenReturn(updatedEntry);

        // Run the test
        MetadataStep.AcquireLease testStep = new TestAcquireLease(testMembers);
        testStep.run();
        WorkerStep nextStep = testStep.nextStep();

        // Check the results
        Mockito.verify(testMembers.cmsClient, times(1)).updateMetadataEntry(
            CmsEntry.MetadataStatus.IN_PROGRESS,
            CmsEntry.Metadata.getLeaseExpiry(TestAcquireLease.milliSinceEpoch, CmsEntry.Metadata.MAX_ATTEMPTS),
            CmsEntry.Metadata.MAX_ATTEMPTS
        );
        assertEquals(nextStepClass, nextStep.getClass());
    }

    static Stream<Arguments> provideMigrateTemplatesArgs() {
        return Stream.of(
            // We were able to acquire the lease
            Arguments.of(                
                Optional.of(new CmsEntry.Metadata(
                    CmsEntry.MetadataStatus.COMPLETED,
                    String.valueOf(42),
                    1
                )),
                MetadataStep.ExitPhaseSuccess.class
            ),

            // We were unable to acquire the lease
            Arguments.of(Optional.empty(), MetadataStep.GetEntry.class)
        );
    }

    @ParameterizedTest
    @MethodSource("provideMigrateTemplatesArgs")
    void MigrateTemplates_AsExpected(Optional<CmsEntry.Metadata> updatedEntry, Class<?> nextStepClass) {
        // Set up the test
        var existingEntry = Optional.of(new CmsEntry.Metadata(
            CmsEntry.MetadataStatus.IN_PROGRESS,
            String.valueOf(42),
            1
        ));
        testMembers.cmsEntry = existingEntry;
        
        GlobalMetadata.Data testGlobalMetadata = Mockito.mock(GlobalMetadata.Data.class);
        ObjectNode testNode = Mockito.mock(ObjectNode.class);
        ObjectNode testTransformedNode = Mockito.mock(ObjectNode.class);
        Mockito.when(testMembers.metadataFactory.fromRepo(testMembers.snapshotName)).thenReturn(testGlobalMetadata);
        Mockito.when(testGlobalMetadata.toObjectNode()).thenReturn(testNode);
        Mockito.when(testMembers.transformer.transformGlobalMetadata(testNode)).thenReturn(testTransformedNode);
        Mockito.when(testMembers.cmsClient.updateMetadataEntry(
            CmsEntry.MetadataStatus.COMPLETED,
            existingEntry.get().leaseExpiry,
            existingEntry.get().numAttempts
        
        )).thenReturn(updatedEntry);

        // Run the test
        MetadataStep.MigrateTemplates testStep = new MetadataStep.MigrateTemplates(testMembers);
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
                    workItem.documentId.equals(OpenSearchCmsClient.CMS_METADATA_DOC_ID);
            })
        );
        Mockito.verify(testMembers.metadataFactory, times(1)).fromRepo(
            testMembers.snapshotName
        );
        Mockito.verify(testMembers.transformer, times(1)).transformGlobalMetadata(
            testNode
        );
        Mockito.verify(testMembers.metadataCreator, times(1)).create(
            testTransformedNode
        );
        Mockito.verify(testMembers.cmsClient, times(1)).updateMetadataEntry(
            CmsEntry.MetadataStatus.COMPLETED,
            existingEntry.get().leaseExpiry,
            existingEntry.get().numAttempts
        );
        Mockito.verify(testMembers.globalState, times(1)).updateWorkItem(
            null
        );

        assertEquals(nextStepClass, nextStep.getClass());
    }

    public static class TestRandomWait extends MetadataStep.RandomWait {
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
        MetadataStep.RandomWait testStep = new TestRandomWait(testMembers);
        testStep.run();
        WorkerStep nextStep = testStep.nextStep();

        // Check the results
        assertEquals(MetadataStep.GetEntry.class, nextStep.getClass());
    }

    @Test
    void ExitPhaseSuccess_AsExpected() {
        // Run the test
        MetadataStep.ExitPhaseSuccess testStep = new MetadataStep.ExitPhaseSuccess(testMembers);
        testStep.run();
        WorkerStep nextStep = testStep.nextStep();

        // Check the results
        Mockito.verify(testMembers.globalState, times(1)).updatePhase(
            GlobalState.Phase.METADATA_COMPLETED
        );
        assertEquals(null, nextStep);
    }

    @Test
    void ExitPhaseFailed_AsExpected() {
        // Set up the test
        MaxAttemptsExceeded e = new MaxAttemptsExceeded();

        var existingEntry = Optional.of(new CmsEntry.Metadata(
            CmsEntry.MetadataStatus.IN_PROGRESS,
            String.valueOf(42),
            1
        ));
        testMembers.cmsEntry = existingEntry;

        // Run the test
        MetadataStep.ExitPhaseFailed testStep = new MetadataStep.ExitPhaseFailed(testMembers, e);
        testStep.run();
        assertThrows(MaxAttemptsExceeded.class, () -> {
            testStep.nextStep();
        });

        // Check the results
        Mockito.verify(testMembers.cmsClient, times(1)).updateMetadataEntry(
            CmsEntry.MetadataStatus.FAILED,
            existingEntry.get().leaseExpiry,
            existingEntry.get().numAttempts
        );
        Mockito.verify(testMembers.globalState, times(1)).updatePhase(
            GlobalState.Phase.METADATA_FAILED
        );
    }
}
