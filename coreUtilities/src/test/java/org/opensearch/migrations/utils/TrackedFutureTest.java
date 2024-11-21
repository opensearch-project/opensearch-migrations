package org.opensearch.migrations.utils;

import java.util.concurrent.Semaphore;

import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

@Slf4j
class TrackedFutureTest {

    @Test
    public void test() throws Exception {
        final int ITERATIONS = 5;
        TrackedFuture<String, String> base = new TextTrackedFuture<>("initial future");
        var tf = base;
        var tfSemaphore = new Semaphore(0);
        var observerSemaphore = new Semaphore(0);
        for (int i = 0; i < ITERATIONS; ++i) {
            int finalI = i;
            var lastTf = tf;
            tf = tf.thenApply(v -> {
                try {
                    observerSemaphore.release();
                    tfSemaphore.acquire();
                    log.atInfo().setMessage("tf[{}]{}").addArgument(finalI).addArgument(lastTf).log();
                } catch (InterruptedException e) {
                    throw Lombok.sneakyThrow(e);
                }
                return v + "," + finalI;
            }, () -> "run for " + finalI);
        }
        base.future.completeAsync(() -> "");
        TrackedFuture<String, String> finalTf = tf;
        for (int i = 0; i < ITERATIONS; ++i) {
            observerSemaphore.acquire();
            tfSemaphore.release();
            Thread.sleep(10);
            int finalI = i;
            log.atInfo().setMessage("top tf after {} releases={}").addArgument(finalI).addArgument(finalTf).log();
        }
        log.atInfo().setMessage("final tf after any ancestor culls={}").addArgument(finalTf).log();
    }
}
