package org.opensearch.migrations.bulkload.common.retry;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import reactor.test.scheduler.VirtualTimeScheduler;

class EvidenceWindowTest {

    @Test
    void aRequestWithoutItemResultsIsOneRequestPointAndNoDocumentPoints() {
        var window = new EvidenceWindow();
        window.record(1, true, 0, 0);        // a 1000-document request that got a 500
        window.record(2, false, 1000, 0);    // a 1000-document request that succeeded
        window.record(3, false, 10, 2);      // a 2xx with two items that failed for the target's reasons

        Assertions.assertEquals(new EvidenceWindow.Evidence(3, 1, 1010, 2), window.othersThan(List.of()));
    }

    @Test
    void aBatchAndItsAncestorsAreNeverEvidenceAboutIt() {
        var window = new EvidenceWindow();
        window.record(1, true, 0, 0);        // the batch that 3 was split from
        window.record(3, true, 0, 0);        // 3 itself
        window.record(2, false, 100, 4);     // another batch

        Assertions.assertEquals(new EvidenceWindow.Evidence(1, 0, 100, 4), window.othersThan(List.of(1L, 3L)));
    }

    @Test
    void theRequestRateNeedsOtherRequests() {
        Assertions.assertTrue(new EvidenceWindow.Evidence(20, 1, 0, 0).requestsFailedAtMost(5));
        Assertions.assertFalse(new EvidenceWindow.Evidence(20, 2, 0, 0).requestsFailedAtMost(5));
        Assertions.assertTrue(new EvidenceWindow.Evidence(20, 20, 0, 0).requestsFailedAtMost(100));
        Assertions.assertFalse(new EvidenceWindow.Evidence(0, 0, 1000, 0).requestsFailedAtMost(100));
    }

    @Test
    void attemptsOlderThanTheWindowAreForgotten() {
        var scheduler = VirtualTimeScheduler.getOrSet();
        try {
            var window = new EvidenceWindow();
            window.record(1, true, 0, 0);
            scheduler.advanceTimeBy(EvidenceWindow.WINDOW.minus(Duration.ofSeconds(1)));
            window.record(1, false, 5, 0);
            Assertions.assertEquals(new EvidenceWindow.Evidence(2, 1, 5, 0), window.othersThan(List.of()));

            scheduler.advanceTimeBy(Duration.ofSeconds(2));
            Assertions.assertEquals(new EvidenceWindow.Evidence(1, 0, 5, 0), window.othersThan(List.of()));
        } finally {
            VirtualTimeScheduler.reset();
        }
    }
}
