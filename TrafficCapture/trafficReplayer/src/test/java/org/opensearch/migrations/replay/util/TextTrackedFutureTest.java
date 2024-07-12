package org.opensearch.migrations.replay.util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;

import lombok.SneakyThrows;

@WrapWithNettyLeakDetection(disableLeakChecks = true)
class TextTrackedFutureTest {
    @SneakyThrows
    private static void sneakyWait(CompletableFuture o) {
        o.get(5, TimeUnit.MINUTES);
    }

    private void notify(CompletableFuture o) {
        o.complete(1);
    }

    @Test
    public void futureWithThreeStages() throws Exception {
        CompletableFuture notifier1 = new CompletableFuture();
        CompletableFuture notifier2 = new CompletableFuture();
        CompletableFuture notifier3 = new CompletableFuture();

        var stcf1 = new TextTrackedFuture<>(CompletableFuture.supplyAsync(() -> {
            sneakyWait(notifier1);
            return 1;
        }), () -> "A");
        final var id1 = System.identityHashCode(stcf1);
        final var id1Bktd = "[" + id1 + "] ";
        Assertions.assertEquals(id1Bktd + "A[…]", TrackedFutureStringFormatter.format(stcf1));

        var stcf2 = stcf1.map(f -> f.thenApplyAsync(x -> {
            sneakyWait(notifier2);
            return x * 10 + 1;
        }), () -> "B");
        final var id2 = System.identityHashCode(stcf2);
        final var id2Bktd = "[" + id2 + "] ";
        Assertions.assertEquals(id2Bktd + "B[…]<-" + id1Bktd + "A[…]", TrackedFutureStringFormatter.format(stcf2));

        var stcf3 = stcf2.map(f -> f.thenApplyAsync(x -> {
            sneakyWait(notifier3);
            return x * 10 + 1;
        }), () -> "C");
        final var id3 = System.identityHashCode(stcf3);
        final var id3Bktd = "[" + id3 + "] ";

        Assertions.assertEquals(id1Bktd + "A[…]", TrackedFutureStringFormatter.format(stcf1));
        Assertions.assertEquals(id2Bktd + "B[…]<-" + id1Bktd + "A[…]", TrackedFutureStringFormatter.format(stcf2));
        Assertions.assertEquals(
            id3Bktd + "C[…]<-" + id2Bktd + "B[…]<-" + id1Bktd + "A[…]",
            TrackedFutureStringFormatter.format(stcf3)
        );

        Assertions.assertEquals(
            "[{\"idHash\":" + id1 + ",\"label\":\"A\",\"value\":\"…\"}]",
            TrackedFutureJsonFormatter.format(stcf1)
        );
        Assertions.assertEquals(
            "["
                + "{\"idHash\":"
                + id2
                + ",\"label\":\"B\",\"value\":\"…\"},"
                + "{\"idHash\":"
                + id1
                + ",\"label\":\"A\",\"value\":\"…\"}]",
            TrackedFutureJsonFormatter.format(stcf2)
        );
        Assertions.assertEquals(
            "["
                + "{\"idHash\":"
                + id3
                + ",\"label\":\"C\",\"value\":\"…\"},"
                + "{\"idHash\":"
                + id2
                + ",\"label\":\"B\",\"value\":\"…\"},"
                + "{\"idHash\":"
                + id1
                + ",\"label\":\"A\",\"value\":\"…\"}]",
            TrackedFutureJsonFormatter.format(stcf3)
        );

        notifyAndWaitForGet(stcf1, notifier1);
        Assertions.assertEquals(id1Bktd + "A[^]", TrackedFutureStringFormatter.format(stcf1));
        Assertions.assertEquals(id2Bktd + "B[…]<-" + id1Bktd + "A[^]", TrackedFutureStringFormatter.format(stcf2));
        Assertions.assertEquals(
            id3Bktd + "C[…]<-" + id2Bktd + "B[…]<-" + id1Bktd + "A[^]",
            TrackedFutureStringFormatter.format(stcf3)
        );
        Assertions.assertEquals(
            id3Bktd + "C[…]<-" + id2Bktd + "B[…]<-" + id1Bktd + "A[1]",
            stcf3.formatAsString(TextTrackedFutureTest::formatCompletableFuture)
        );

        Assertions.assertEquals(
            "[{\"idHash\":" + id1 + ",\"label\":\"A\",\"value\":\"^\"}]",
            TrackedFutureJsonFormatter.format(stcf1)
        );
        Assertions.assertEquals(
            "["
                + "{\"idHash\":"
                + id2
                + ",\"label\":\"B\",\"value\":\"…\"},"
                + "{\"idHash\":"
                + id1
                + ",\"label\":\"A\",\"value\":\"^\"}]",
            TrackedFutureJsonFormatter.format(stcf2)
        );
        Assertions.assertEquals(
            "["
                + "{\"idHash\":"
                + id3
                + ",\"label\":\"C\",\"value\":\"…\"},"
                + "{\"idHash\":"
                + id2
                + ",\"label\":\"B\",\"value\":\"…\"},"
                + "{\"idHash\":"
                + id1
                + ",\"label\":\"A\",\"value\":\"^\"}]",
            TrackedFutureJsonFormatter.format(stcf3)
        );

        notifyAndWaitForGet(stcf2, notifier2);
        Assertions.assertEquals(id2Bktd + "B[^]<-" + id1Bktd + "A[^]", TrackedFutureStringFormatter.format(stcf2));
        Assertions.assertEquals(id1Bktd + "A[^]", TrackedFutureStringFormatter.format(stcf1));
        Assertions.assertEquals(
            id3Bktd + "C[…]<-" + id2Bktd + "B[^]<-" + id1Bktd + "A[^]",
            TrackedFutureStringFormatter.format(stcf3)
        );
        Assertions.assertEquals(
            id3Bktd + "C[…]<-" + id2Bktd + "B[11]<-" + id1Bktd + "A[1]",
            stcf3.formatAsString(TextTrackedFutureTest::formatCompletableFuture)
        );

        Assertions.assertEquals(
            "[{\"idHash\":" + id1 + ",\"label\":\"A\",\"value\":\"^\"}]",
            TrackedFutureJsonFormatter.format(stcf1)
        );
        Assertions.assertEquals(
            "["
                + "{\"idHash\":"
                + id2
                + ",\"label\":\"B\",\"value\":\"^\"},"
                + "{\"idHash\":"
                + id1
                + ",\"label\":\"A\",\"value\":\"^\"}]",
            TrackedFutureJsonFormatter.format(stcf2)
        );
        Assertions.assertEquals(
            "["
                + "{\"idHash\":"
                + id3
                + ",\"label\":\"C\",\"value\":\"…\"},"
                + "{\"idHash\":"
                + id2
                + ",\"label\":\"B\",\"value\":\"^\"},"
                + "{\"idHash\":"
                + id1
                + ",\"label\":\"A\",\"value\":\"^\"}]",
            TrackedFutureJsonFormatter.format(stcf3)
        );
        Assertions.assertEquals(
            "["
                + "{\"idHash\":"
                + id3
                + ",\"label\":\"C\",\"value\":\"…\"},"
                + "{\"idHash\":"
                + id2
                + ",\"label\":\"B\",\"value\":\"11\"},"
                + "{\"idHash\":"
                + id1
                + ",\"label\":\"A\",\"value\":\"1\"}]",
            stcf3.formatAsJson(TextTrackedFutureTest::formatCompletableFuture)
        );

        // A is clipped because of grandparent culling
        notifyAndWaitForGet(stcf3, notifier3);
        Assertions.assertEquals(id3Bktd + "C[^]<-" + id2Bktd + "B[^]", TrackedFutureStringFormatter.format(stcf3));
        Assertions.assertEquals(id1Bktd + "A[^]", TrackedFutureStringFormatter.format(stcf1));
        Assertions.assertEquals(id2Bktd + "B[^]", TrackedFutureStringFormatter.format(stcf2));

        Assertions.assertEquals(
            "["
                + "{\"idHash\":"
                + id3
                + ",\"label\":\"C\",\"value\":\"111\"},"
                + "{\"idHash\":"
                + id2
                + ",\"label\":\"B\",\"value\":\"11\"}]",
            stcf3.formatAsJson(TextTrackedFutureTest::formatCompletableFuture)
        );
    }

    public static String formatCompletableFuture(TrackedFuture<String, ?> cf) {
        try {
            return "" + cf.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "EXCEPTION";
        } catch (ExecutionException e) {
            return "EXCEPTION";
        }
    }

    private void notifyAndWaitForGet(TrackedFuture<String, Integer> stcf, CompletableFuture lockObject)
        throws Exception {
        notify(lockObject);
        stcf.get();
    }
}
