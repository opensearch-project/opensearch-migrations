package com.rfs.cms;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class CmsEntryTest {

    static Stream<Arguments> provide_Metadata_getLeaseExpiry_HappyPath_args() {
        // Generate an argument for each possible number of attempts
        Stream<Arguments> argStream = Stream.of();
        for (int i = 1; i <= CmsEntry.Metadata.MAX_ATTEMPTS; i++) {
            argStream = Stream.concat(argStream, Stream.of(Arguments.of(i)));
        }
        return argStream;
    }

    @ParameterizedTest
    @MethodSource("provide_Metadata_getLeaseExpiry_HappyPath_args")
    void Metadata_getLeaseExpiry_HappyPath(int numAttempts) {
        // Run the test
        String result = CmsEntry.Metadata.getLeaseExpiry(0, numAttempts);

        // Check the results
        assertEquals(Long.toString(CmsEntry.Metadata.METADATA_LEASE_MS * numAttempts), result);
    }

    static Stream<Arguments> provide_Metadata_getLeaseExpiry_UnhappyPath_args() {
        return Stream.of(
            Arguments.of(0),
            Arguments.of(CmsEntry.Metadata.MAX_ATTEMPTS + 1)
        );
    }

    @ParameterizedTest
    @MethodSource("provide_Metadata_getLeaseExpiry_UnhappyPath_args")
    void Metadata_getLeaseExpiry_UnhappyPath(int numAttempts) {
        // Run the test
        assertThrows(CmsEntry.CouldNotFindNextLeaseDuration.class, () -> {
            CmsEntry.Metadata.getLeaseExpiry(0, numAttempts);
        });
    }    
}
