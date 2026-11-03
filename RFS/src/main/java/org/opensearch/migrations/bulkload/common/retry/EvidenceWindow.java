package org.opensearch.migrations.bulkload.common.retry;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

import reactor.core.scheduler.Schedulers;

/**
 * What a process's bulk attempts over the last {@link #WINDOW} say about the target, as two separate series:
 * <ul>
 *   <li><b>Requests:</b> one point per attempt, failed if the target, rather than a document, may be why it didn't
 *       fully succeed: no response, a status other than 2xx, 400 and 413, an unreadable 2xx, or a 2xx with an item that was
 *       throttled or unavailable.</li>
 *   <li><b>Documents:</b> one point per item of a response with per-item results, failed if the item failed with an
 *       error other than the document's own (throttled, unavailable or unknown). A response without per-item results
 *       adds no document points, however many documents it carried.</li>
 * </ul>
 * It is the evidence for whether a failure counts against a document (see {@code OpenSearchClient}): each attempt is
 * recorded with the batch that sent it, and a batch asks about the others only, so its own attempts, and those of the
 * batches it was split from, are never evidence for or against it. Thread-safe.
 */
public class EvidenceWindow {
    public static final Duration WINDOW = Duration.ofMinutes(2);

    /** What other batches' attempts in the window showed. */
    public record Evidence(long requests, long failedRequests, long documents, long failedDocuments) {
        /** Whether there were other requests, and at most {@code percent} (0-100) of them failed. */
        public boolean requestsFailedAtMost(double percent) {
            return requests > 0 && failedRequests * 100.0 <= percent * requests;
        }
    }

    private record Attempt(long atMillis, long batchId, boolean requestFailed, long documents, long failedDocuments) {}

    private final ArrayDeque<Attempt> attempts = new ArrayDeque<>();
    private long requests;
    private long failedRequests;
    private long documents;
    private long failedDocuments;

    // Read from the parallel scheduler on every use so a test's virtual time replaces it.
    private static long now() {
        return Schedulers.parallel().now(TimeUnit.MILLISECONDS);
    }

    /** An attempt by batch {@code batchId}; {@code documents} and {@code failedDocuments} are 0 without item results. */
    public synchronized void record(long batchId, boolean requestFailed, long documents, long failedDocuments) {
        long now = now();
        prune(now);
        attempts.addLast(new Attempt(now, batchId, requestFailed, documents, failedDocuments));
        requests++;
        failedRequests += requestFailed ? 1 : 0;
        this.documents += documents;
        this.failedDocuments += failedDocuments;
    }

    /** What every batch except {@code excluded} did in the last {@link #WINDOW}. */
    public synchronized Evidence othersThan(Collection<Long> excluded) {
        prune(now());
        long ownRequests = 0;
        long ownFailedRequests = 0;
        long ownDocuments = 0;
        long ownFailedDocuments = 0;
        for (var attempt : attempts) {
            if (excluded.contains(attempt.batchId())) {
                ownRequests++;
                ownFailedRequests += attempt.requestFailed() ? 1 : 0;
                ownDocuments += attempt.documents();
                ownFailedDocuments += attempt.failedDocuments();
            }
        }
        return new Evidence(requests - ownRequests, failedRequests - ownFailedRequests,
            documents - ownDocuments, failedDocuments - ownFailedDocuments);
    }

    private void prune(long now) {
        while (!attempts.isEmpty() && now - attempts.peekFirst().atMillis() > WINDOW.toMillis()) {
            var oldest = attempts.removeFirst();
            requests--;
            failedRequests -= oldest.requestFailed() ? 1 : 0;
            documents -= oldest.documents();
            failedDocuments -= oldest.failedDocuments();
        }
    }
}
