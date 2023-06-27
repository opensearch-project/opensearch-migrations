package org.opensearch.migrations.replay.util;

import lombok.SneakyThrows;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

class StringTrackableCompletableFutureTest {
    @SneakyThrows
    private static void sneakyWait(CompletableFuture o) {
        o.get(1, TimeUnit.SECONDS);
    }

    private void notify(CompletableFuture o) {
        o.complete(1);
    }

    @Test
    public void futureWithThreeStages() throws Exception {
        CompletableFuture notifier1 = new CompletableFuture();
        CompletableFuture notifier2 = new CompletableFuture();
        CompletableFuture notifier3 = new CompletableFuture();

        var stcf1 = new StringTrackableCompletableFuture<>(CompletableFuture.supplyAsync(()->{
            sneakyWait(notifier1);
            return 1;
        }),
                ()->"A");
        Assertions.assertEquals("A[…]", stcf1.toString());

        var stcf2 = stcf1.map(f->f.thenApplyAsync(x->{
            sneakyWait(notifier2);
            return x*10+1;
        }),
                ()->"B");
        Assertions.assertEquals("A[…]->B[…]", stcf2.toString());

        var stcf3 = stcf2.map(f->f.thenApplyAsync(x->{
                    sneakyWait(notifier3);
                    return x*10+1;
                }),
                ()->"C");

        Assertions.assertEquals("A[…]", stcf1.toString());
        Assertions.assertEquals("A[…]->B[…]", stcf2.toString());
        Assertions.assertEquals("A[…]->B[…]->C[…]", stcf3.toString());

        notifyAndCheckNewDiagnosticValue(stcf1, notifier1, "A[^]");
        Assertions.assertEquals("A[^]->B[…]", stcf2.toString());
        Assertions.assertEquals("A[^]->B[…]->C[…]", stcf3.toString());
        notifyAndCheckNewDiagnosticValue(stcf2, notifier2, "A[^]->B[^]");
        Assertions.assertEquals("A[^]", stcf1.toString());
        Assertions.assertEquals("A[^]->B[^]->C[…]", stcf3.toString());
        notifyAndCheckNewDiagnosticValue(stcf3, notifier3, "A[^]->B[^]->C[^]");
        Assertions.assertEquals("A[^]", stcf1.toString());
        Assertions.assertEquals("A[^]->B[^]", stcf2.toString());
    }

    private void notifyAndCheckNewDiagnosticValue(DiagnosticTrackableCompletableFuture<String, Integer> stcf,
                                                  CompletableFuture lockObject, String expectedValue) throws Exception {
        notify(lockObject);
        stcf.get();
        Assertions.assertEquals(expectedValue, stcf.toString());
    }
}