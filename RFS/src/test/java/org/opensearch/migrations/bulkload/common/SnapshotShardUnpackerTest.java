package org.opensearch.migrations.bulkload.common;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

import org.opensearch.migrations.bulkload.models.ShardFileInfo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SnapshotShardUnpackerTest {

    @Test
    void testUnpack_ThrowsCouldNotUnpackShard_WhenFileUnpackingFails(@TempDir Path tempDirectory) throws Exception {
        // Arrange
        var mockRepoAccessor = mock(SourceRepoAccessor.class);
        var mockFileMetadata = mock(ShardFileInfo.class);
        
        when(mockFileMetadata.getPhysicalName()).thenReturn("test-file.dat");
        when(mockFileMetadata.getName()).thenReturn("regular-file");
        when(mockFileMetadata.getLength()).thenReturn(100L);
        when(mockFileMetadata.getNumberOfParts()).thenReturn(1L);
        when(mockFileMetadata.partName(0L)).thenReturn("part-0");
        
        // Simulate a RuntimeException wrapping IOException when trying to access the blob file
        when(mockRepoAccessor.getBlobFile(anyString(), anyInt(), anyString()))
            .thenThrow(new SourceRepoAccessor.CouldNotLoadRepoFile("Simulated read failure", 
                new IOException("Simulated read failure")));
        
        var filesToUnpack = Set.of(mockFileMetadata);
        var unpacker = new SnapshotShardUnpacker(
            mockRepoAccessor,
            filesToUnpack,
            tempDirectory,
            "test-index-id",
            0
        );
        
        // Act & Assert
        var exception = assertThrows(
            SnapshotShardUnpacker.CouldNotUnpackShard.class,
            () -> unpacker.unpack()
        );
        
        // Verify the exception details
        // The outer exception is CouldNotUnpackShard with message about the shard
        assertTrue(exception.getMessage().contains("Could not unpack shard"), 
            "Exception message should indicate shard unpacking failure");
        assertTrue(exception.getMessage().contains("test-index-id"), 
            "Exception message should contain the index ID");
        
        // Verify there is a cause (the exception chain is preserved)
        assertInstanceOf(Exception.class, exception.getCause(),
            "Exception should have a cause");
    }
}
