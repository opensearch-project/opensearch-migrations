package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.bulkload.common.OpenSearchClient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenSearchMetadataSinkTest {

    @Mock
    OpenSearchClient client;

    @Test
    void createIndexRunsBlockingClientCallOffSubscriberThread() {
        var clientThread = new AtomicReference<Thread>();
        when(client.createIndex(anyString(), any(), isNull())).thenAnswer(invocation -> {
            clientThread.set(Thread.currentThread());
            return Optional.empty();
        });

        var sink = new OpenSearchMetadataSink(client);
        var metadata = new IndexMetadataSnapshot("test-index", 1, 0, null, null, null);
        var subscriberThread = Thread.currentThread();

        sink.createIndex(metadata).block();

        assertNotNull(clientThread.get());
        assertNotSame(subscriberThread, clientThread.get());
    }
}
