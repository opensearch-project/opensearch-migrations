package org.opensearch.migrations.replay.util;

import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
class DiagnosticTrackableCompletableFutureTest {

    @Test
    public void test() throws Exception {
        final int ITERATIONS = 5;
        DiagnosticTrackableCompletableFuture<String, String> base =
                new StringTrackableCompletableFuture<>("initial future");
        var dcf = base;
        var dcfSemaphore = new Semaphore(0);
        var observerSemaphore = new Semaphore(0);
        for (int i = 0; i< ITERATIONS; ++i) {
            int finalI = i;
            var lastDcf = dcf;
            dcf = dcf.thenApply(v->{
                try {
                    observerSemaphore.release();
                    dcfSemaphore.acquire();
                    log.atInfo().setMessage(()->"dcf[" + finalI+"]"+lastDcf).log();
                } catch (InterruptedException e) {
                    throw Lombok.sneakyThrow(e);
                }
                return v + "," + finalI;
            }, ()->"run for "+ finalI);
        }
        base.future.completeAsync(()->"");
        DiagnosticTrackableCompletableFuture<String, String> finalDcf = dcf;
        for (int i=0; i<ITERATIONS; ++i) {
            observerSemaphore.acquire();
            dcfSemaphore.release();
            Thread.sleep(10);
            int finalI = i;
            log.atInfo().setMessage(()->"top dcf after " + finalI + " releases="+ finalDcf).log();
        }
        log.atInfo().setMessage(()->"final dcf after any ancestor culls=" + finalDcf).log();
    }
}