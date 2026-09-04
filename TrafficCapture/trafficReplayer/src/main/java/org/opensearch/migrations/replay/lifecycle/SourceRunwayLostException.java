package org.opensearch.migrations.replay.lifecycle;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;

import lombok.Getter;
import lombok.NonNull;

@SuppressWarnings("java:S110") // The typed cause must remain a CancellationException for lifecycle propagation.
public final class SourceRunwayLostException extends CancellationException {
    @Getter
    private final SourcePartitionKey partition;

    public SourceRunwayLostException(@NonNull SourcePartitionKey partition) {
        super("source runway was lost for " + partition);
        this.partition = partition;
    }

    public static boolean causedBy(Throwable failure) {
        var current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
            && current.getCause() != null)
        {
            current = current.getCause();
        }
        return current instanceof SourceRunwayLostException;
    }
}
