package com.rfs.worker;

import java.time.Duration;
import java.time.Instant;
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

import com.rfs.cms.CmsClient;
import com.rfs.cms.CmsEntry;
import com.rfs.common.GlobalMetadata;
import com.rfs.transformers.Transformer;
import com.rfs.version_os_2_11.GlobalMetadataCreator_OS_2_11;
import com.rfs.worker.MetadataStep.SharedMembers;

import static org.mockito.ArgumentMatchers.any;
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
                null,
                MetadataStep.CreateEntry.class
            ),

            // The CMS entry has an expired lease and is under the retry limit, so we try to acquire the lease
            Arguments.of(
                new CmsEntry.Metadata(
                    CmsEntry.MetadataStatus.IN_PROGRESS,
                    String.valueOf(Instant.now().minus(Duration.ofDays(1)).toEpochMilli()),
                    CmsEntry.Metadata.MAX_ATTEMPTS - 1
                ),
                MetadataStep.AcquireLease.class
            ),

            // The CMS entry has an expired lease and is at the retry limit, so we exit as failed
            Arguments.of(
                new CmsEntry.Metadata(
                    CmsEntry.MetadataStatus.IN_PROGRESS,
                    String.valueOf(Instant.now().minus(Duration.ofDays(1)).toEpochMilli()),
                    CmsEntry.Metadata.MAX_ATTEMPTS
                ),
                MetadataStep.ExitPhaseFailed.class
            ),

            // The CMS entry has an expired lease and is over the retry limit, so we exit as failed
            Arguments.of(
                new CmsEntry.Metadata(
                    CmsEntry.MetadataStatus.IN_PROGRESS,
                    String.valueOf(Instant.now().minus(Duration.ofDays(1)).toEpochMilli()),
                    CmsEntry.Metadata.MAX_ATTEMPTS + 1
                ),
                MetadataStep.ExitPhaseFailed.class
            ),

            // The CMS entry has valid lease and is under the retry limit, so we back off a bit
            Arguments.of(
                new CmsEntry.Metadata(
                    CmsEntry.MetadataStatus.IN_PROGRESS,
                    String.valueOf(Instant.now().plus(Duration.ofDays(1)).toEpochMilli()),
                    CmsEntry.Metadata.MAX_ATTEMPTS - 1
                ),
                MetadataStep.RandomWait.class
            ),

            // The CMS entry has valid lease and is at the retry limit, so we back off a bit
            Arguments.of(
                new CmsEntry.Metadata(
                    CmsEntry.MetadataStatus.IN_PROGRESS,
                    String.valueOf(Instant.now().plus(Duration.ofDays(1)).toEpochMilli()),
                    CmsEntry.Metadata.MAX_ATTEMPTS
                ),
                MetadataStep.RandomWait.class
            ),

            // The CMS entry is marked as completed, so we exit as success
            Arguments.of(
                new CmsEntry.Metadata(
                    CmsEntry.MetadataStatus.COMPLETED,
                    String.valueOf(Instant.now().plus(Duration.ofDays(1)).toEpochMilli()),
                    CmsEntry.Metadata.MAX_ATTEMPTS - 1
                ),
                MetadataStep.ExitPhaseSuccess.class
            ),

            // The CMS entry is marked as completed, so we exit as success
            Arguments.of(
                new CmsEntry.Metadata(
                    CmsEntry.MetadataStatus.FAILED,
                    String.valueOf(Instant.now().plus(Duration.ofDays(1)).toEpochMilli()),
                    CmsEntry.Metadata.MAX_ATTEMPTS - 1
                ),
                MetadataStep.ExitPhaseFailed.class
            )
        );
    }

    @ParameterizedTest
    @MethodSource("provideGetEntryArgs")
    void GetEntry_AsExpected(CmsEntry.Metadata metadata, Class<?> nextStepClass) {
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
            Arguments.of(true, MetadataStep.MigrateTemplates.class),

            // We were unable to create the CMS entry ourselves, so we do not have the work lease
            Arguments.of(false, MetadataStep.GetEntry.class)
        );
    }

    @ParameterizedTest
    @MethodSource("provideCreateEntryArgs")
    void CreateEntry_AsExpected(boolean createdEntry, Class<?> nextStepClass) {
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

        public TestAcquireLease(SharedMembers members, CmsEntry.Metadata existingEntry) {
            super(members, existingEntry);
        }

        @Override
        protected Instant getNow() {
            return Instant.ofEpochMilli(milliSinceEpoch);
        }
    }

    static Stream<Arguments> provideAcquireLeaseArgs() {
        return Stream.of(
            // We were able to acquire the lease
            Arguments.of(true, MetadataStep.MigrateTemplates.class),

            // We were unable to acquire the lease
            Arguments.of(false, MetadataStep.RandomWait.class)
        );
    }

    @ParameterizedTest
    @MethodSource("provideAcquireLeaseArgs")
    void AcquireLease_AsExpected(boolean acquiredLease, Class<?> nextStepClass) {
        // Set up the test
        CmsEntry.Metadata existingEntry = new CmsEntry.Metadata(
            CmsEntry.MetadataStatus.IN_PROGRESS,
            "0",
            CmsEntry.Metadata.MAX_ATTEMPTS - 1
        );

        Mockito.when(testMembers.cmsClient.updateMetadataEntry(
            any(CmsEntry.MetadataStatus.class), anyString(), anyInt()
        )).thenReturn(acquiredLease);

        // Run the test
        MetadataStep.AcquireLease testStep = new TestAcquireLease(testMembers, existingEntry);
        testStep.run();
        WorkerStep nextStep = testStep.nextStep();

        // Check the results
        Mockito.verify(testMembers.cmsClient, times(1)).updateMetadataEntry(
            CmsEntry.MetadataStatus.IN_PROGRESS,
            String.valueOf(TestAcquireLease.milliSinceEpoch + CmsEntry.Metadata.METADATA_LEASE_MS),
            CmsEntry.Metadata.MAX_ATTEMPTS
        );
        assertEquals(nextStepClass, nextStep.getClass());
    }
    
}
