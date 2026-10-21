package org.opensearch.migrations;

import java.io.IOException;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;

import org.opensearch.migrations.bulkload.workcoordination.IWorkCoordinator.WorkItemAndDuration.WorkItem;
import org.opensearch.migrations.reindexer.doccount.ShardDocCountRecord;
import org.opensearch.migrations.reindexer.doccount.ShardDocCountSink;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShardDocCountReporterTest {
    @Test
    void stampsIdentityAndPreservesUnknownCounts() {
        var sink = mock(ShardDocCountSink.class);
        when(sink.write(any())).thenReturn(Mono.empty());
        when(sink.flush()).thenReturn(Mono.empty());
        var workItem = new WorkItem("idx", 2, 42L);
        RfsMigrateDocuments.shardDocCountReporter(sink, "session", "worker", "snapshot")
            .report(workItem, OptionalLong.empty());
        var captured = ArgumentCaptor.forClass(ShardDocCountRecord.class);
        verify(sink).write(captured.capture());
        var record = captured.getValue();
        assertEquals("session", record.getSessionId());
        assertEquals("worker", record.getWorkerId());
        assertEquals(workItem.toString(), record.getWorkItemId());
        assertNull(record.getLiveLuceneDocCount());
        assertEquals("snapshot", record.getSnapshotName());
    }

    @Test
    void flushRunsAfterWriteAndFailurePropagates() {
        var sink = mock(ShardDocCountSink.class);
        var written = new AtomicBoolean();
        when(sink.write(any())).thenReturn(Mono.fromRunnable(() -> written.set(true)));
        when(sink.flush()).thenAnswer(call -> {
            assertTrue(written.get());
            return Mono.error(new IOException("upload failed"));
        });
        assertThrows(Exception.class, () -> RfsMigrateDocuments.shardDocCountReporter(sink, "s", "w", "snapshot")
            .report(new WorkItem("idx", 0, 0L), OptionalLong.of(0)));
    }
}
