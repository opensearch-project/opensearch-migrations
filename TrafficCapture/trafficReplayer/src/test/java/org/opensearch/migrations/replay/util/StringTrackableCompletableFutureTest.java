package org.opensearch.migrations.replay.util;

import lombok.SneakyThrows;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@WrapWithNettyLeakDetection(disableLeakChecks = true)
class StringTrackableCompletableFutureTest {
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

        var stcf1 = new StringTrackableCompletableFuture<>(CompletableFuture.supplyAsync(()->{
            sneakyWait(notifier1);
            return 1;
        }),
                ()->"A");
        var id1 = "[" + System.identityHashCode(stcf1) + "] ";
        Assertions.assertEquals(id1 + "A[…]", stcf1.toString());

        var stcf2 = stcf1.map(f->f.thenApplyAsync(x->{
            sneakyWait(notifier2);
            return x*10+1;
        }),
                ()->"B");
        var id2 = "[" + System.identityHashCode(stcf2) + "] ";
        Assertions.assertEquals(id2 + "B[…]<-" + id1 + "A[…]", stcf2.toString());

        var stcf3 = stcf2.map(f->f.thenApplyAsync(x->{
                    sneakyWait(notifier3);
                    return x*10+1;
                }),
                ()->"C");
        var id3 = "[" + System.identityHashCode(stcf3) + "] ";

        Assertions.assertEquals(id1 + "A[…]", stcf1.toString());
        Assertions.assertEquals(id2 + "B[…]<-" + id1 + "A[…]", stcf2.toString());
        Assertions.assertEquals(id3 + "C[…]<-" + id2 + "B[…]<-" + id1 + "A[…]", stcf3.toString());

        notifyAndCheckNewDiagnosticValue(stcf1, notifier1, id1 + "A[^]");
        Assertions.assertEquals(id2 + "B[…]<-" + id1 + "A[^]", stcf2.toString());
        Assertions.assertEquals(id3 + "C[…]<-" + id2 + "B[…]<-" + id1 + "A[^]", stcf3.toString());
        Assertions.assertEquals(id3 + "C[…]<-" + id2 + "B[…]<-" + id1 + "A[1]",
                stcf3.formatAsString(StringTrackableCompletableFutureTest::formatCompletableFuture));
        notifyAndCheckNewDiagnosticValue(stcf2, notifier2, id2 + "B[^]<-" + id1 + "A[^]");
        Assertions.assertEquals(id1 + "A[^]", stcf1.toString());
        Assertions.assertEquals(id3 + "C[…]<-" + id2 + "B[^]<-" + id1 + "A[^]", stcf3.toString());
        Assertions.assertEquals(id3 + "C[…]<-" + id2 + "B[11]<-" + id1 + "A[1]",
                stcf3.formatAsString(StringTrackableCompletableFutureTest::formatCompletableFuture));
        notifyAndCheckNewDiagnosticValue(stcf3, notifier3,
                id3 + "C[^]<-" + id2  +"B[^]<-" + id1 + "A[^]");
        Assertions.assertEquals(id1 + "A[^]", stcf1.toString());
        Assertions.assertEquals(id2 + "B[^]<-" + id1 + "A[^]", stcf2.toString());

    }

    public static String formatCompletableFuture(DiagnosticTrackableCompletableFuture<String,?> cf) {
        try {
            return "" + cf.get();
        } catch (ExecutionException | InterruptedException e) {
            return "EXCEPTION";
        }
    }

    private void notifyAndCheckNewDiagnosticValue(DiagnosticTrackableCompletableFuture<String, Integer> stcf,
                                                  CompletableFuture lockObject, String expectedValue) throws Exception {
        notify(lockObject);
        stcf.get();
        Assertions.assertEquals(expectedValue, stcf.toString());
    }
}