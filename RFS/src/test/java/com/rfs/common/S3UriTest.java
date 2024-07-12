package com.rfs.common;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class S3UriTest {
    static Stream<Arguments> provideUris() {
        return Stream.of(
            Arguments.of("s3://bucket-name", "bucket-name", "", "s3://bucket-name"),
            Arguments.of("s3://bucket-name/", "bucket-name", "", "s3://bucket-name"),
            Arguments.of("s3://bucket-name/with/suffix", "bucket-name", "with/suffix", "s3://bucket-name/with/suffix"),
            Arguments.of("s3://bucket-name/with/suffix/", "bucket-name", "with/suffix", "s3://bucket-name/with/suffix")
        );
    }

    @ParameterizedTest
    @MethodSource("provideUris")
    void S3Uri_AsExpected(String uri, String expectedBucketName, String expectedPrefix, String expectedUri) {
        S3Uri s3Uri = new S3Uri(uri);
        assertEquals(expectedBucketName, s3Uri.bucketName);
        assertEquals(expectedPrefix, s3Uri.key);
        assertEquals(expectedUri, s3Uri.uri);
    }
}
