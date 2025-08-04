package org.opensearch.migrations.bulkload;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Included here for learning and validation of flux behavior
@Slf4j
public class FluxTest {

    @Test
    public void test_showsDoFinallyBottomToTop_whenSingleThreaded() {
        var topFinallyLatch = new CountDownLatch(1);
        var bottomFinallyLatch = new CountDownLatch(1);
        Flux.just(1)
            .doFinally(s -> topFinallyLatch.countDown())
            .publishOn(Schedulers.boundedElastic()) // Need to switch threads to wait on bottom latch with while top executes
            .doFinally(s -> {
                    latchWait(topFinallyLatch, false, false);
                    bottomFinallyLatch.countDown();
                }
            )
            .subscribeOn(Schedulers.single())
            .subscribe();
        latchWait(bottomFinallyLatch, true, false);
    }

    @Test
    public void test_showsDoFinallyCanBeOutOfOrder() {
        var firstFinallyLatch = new CountDownLatch(1);
        var secondFinallyLatch = new CountDownLatch(1);
        var atomic = new AtomicInteger(0);
        Flux.just(1)
            .doFinally(s ->
                {
                    atomic.accumulateAndGet(2, (a, b) -> a * b);
                    System.out.println(Thread.currentThread().getName() + ": First");
                    firstFinallyLatch.countDown();
                }
            )
            .publishOn(Schedulers.parallel())
            .doFinally(s -> {
                latchWait(firstFinallyLatch, false, false);
                atomic.accumulateAndGet(1, Integer::sum);
                System.out.println(Thread.currentThread().getName() + ": Second");
                secondFinallyLatch.countDown();
            })
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(System.out::println);
        latchWait(secondFinallyLatch, true, false);
        assertEquals(1, atomic.get());
    }


    @Test
    public void test_showsDoFinallyRunsBeforeFlatMapFinishes() {
        // Shows doFinally finished even though Mono never did.
        // This is because even though they are both on "boundedElastic", they are on different boundedElastic threads
        // due to the inner publishOn
        {
            var finallyLatch = new CountDownLatch(1);
            var neverLatch = new CountDownLatch(1);
            Flux.just(1)
                .publishOn(Schedulers.boundedElastic())
                .doFinally(s -> finallyLatch.countDown())
                .flatMapSequential(s ->
                    Mono.just(1)
                        .publishOn(Schedulers.boundedElastic())
                        .doOnNext(i -> latchWait(neverLatch, false, false))
                )
                .subscribe();
            latchWait(finallyLatch, true, false);
        }


        // When the publishOn is placed outside the flatMapSequential, then it is not finished because the same
        // Thread that is blocked on the latchWait(neverLatch, false) is used for the doFinally
        {
            var finallyLatch = new CountDownLatch(1);
            var neverLatch = new CountDownLatch(1);
            Flux.just(1)
                .publishOn(Schedulers.boundedElastic())
                .doFinally(s -> finallyLatch.countDown())
                .flatMapSequential(s ->
                    Mono.just(1)
                        .doOnNext(i -> latchWait(neverLatch, false, false))
                )
                .publishOn(Schedulers.boundedElastic())
                .subscribe();
            latchWait(finallyLatch, true, true);
        }

        // How do we ensure in isolation the doFinally isn't blocked, we ensure a separate thread for
        // the blocking actions in do finally
        {
            var finallyLatch = new CountDownLatch(1);
            var neverLatch = new CountDownLatch(1);
            Flux.just(1)
                .publishOn(Schedulers.boundedElastic())
                .doFinally(s -> Schedulers.boundedElastic().schedule(
                    finallyLatch::countDown)
                )
                .flatMapSequential(s ->
                    Mono.just(1)
                        .doOnNext(i -> latchWait(neverLatch, false, false))
                )
                .subscribe();
            latchWait(finallyLatch, true, true);
        }
        // Why did that not work? Because we still used the same thread for the blocking action in flatMapSequential as
        // what triggers the doFinally scheduling to occur.

        // We need to be especially careful which thread any blocking actions occur on e.g.
        // Outer assertion: we expect this whole block to TIME OUT
        Assertions.assertThrows(org.opentest4j.AssertionFailedError.class, () ->
            Assertions.assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
        {
            var finallyLatch = new CountDownLatch(1);
            var neverLatch = new CountDownLatch(1);
            Flux.just(1) // Runs on "Main"
                .doFinally(s -> // Would have run on "Main", (Won't get here)
                    Schedulers.boundedElastic().schedule(
                        finallyLatch::countDown // would have run on boundedElastic-a (Won't get here)
                    )
                )
                .flatMapSequential(s -> // Runs on "Main"
                    Mono.just(1) // Runs on "Main"
                        .doOnNext(i -> latchWait(neverLatch, false, false)) // Runs on "Main" <- Main gets stuck here
                )
                .subscribe(); // Runs on "Main"
            latchWait(finallyLatch, true, false); // Would have run on "Main", (Won't get here)
        }));
    }

    @SneakyThrows
    final void latchWait(CountDownLatch latch, boolean timeout, boolean expectedTimeout) {
        if (timeout) {
            var success = latch.await(10, TimeUnit.MILLISECONDS);
            Assertions.assertEquals(!expectedTimeout, success, "Expected latch wait success " + !expectedTimeout + " but got " + success);
        } else {
            Assertions.assertFalse(expectedTimeout, "Must not expect timeout if running without timeout.");
            while (true) {
                try {
                    latch.await();
                    break;
                } catch (InterruptedException e) {
                    // Reset Thread Interrupt and make sonarqube happy
                    Thread.currentThread().interrupt();
                    boolean wasInterrupted = Thread.interrupted();
                    assert wasInterrupted : "Expected interrupt after calling interrupt";
                }
            }
        }
    }
}
