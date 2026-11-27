package org.opensearch.migrations.bulkload.common;

import java.util.ArrayList;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The fail-fast table: an OutOfMemoryError anywhere in a cause chain halts with the OOM exit code
 * (the sweep escalates), any other Error halts with the fatal-error code (the sweep releases), and
 * plain exceptions pass through so retry paths keep handling them.
 */
class FatalErrorsTest {

    @Test
    void outOfMemoryAnywhereInTheChainWinsOverOtherErrors() {
        Assertions.assertEquals(3, FatalErrors.exitCodeFor(new OutOfMemoryError("heap")).orElseThrow());
        Assertions.assertEquals(3,
            FatalErrors.exitCodeFor(new RuntimeException("send failed",
                new OutOfMemoryError("Direct buffer memory"))).orElseThrow());
        // Netty's OutOfDirectMemoryError extends OutOfMemoryError; a plain subclass stands in here.
        Assertions.assertEquals(3,
            FatalErrors.exitCodeFor(new OutOfMemoryError("failed to allocate 16777216 byte(s)") {
            }).orElseThrow());
    }

    @Test
    void nonMemoryErrorsGetTheFatalErrorCode() {
        Assertions.assertEquals(8, FatalErrors.exitCodeFor(new NoClassDefFoundError("gone")).orElseThrow());
        Assertions.assertEquals(8,
            FatalErrors.exitCodeFor(new RuntimeException("wrapped", new AssertionError("broken"))).orElseThrow());
    }

    @Test
    void plainExceptionsAreNotFatal() {
        Assertions.assertTrue(FatalErrors.exitCodeFor(new RuntimeException("transient")).isEmpty());
        Assertions.assertTrue(
            FatalErrors.exitCodeFor(new RuntimeException("io", new java.io.IOException("reset"))).isEmpty());
    }

    @Test
    void selfReferencingCauseChainsTerminate() {
        var a = new RuntimeException("a");
        var b = new RuntimeException("b", a);
        a.initCause(b);
        Assertions.assertTrue(FatalErrors.exitCodeFor(a).isEmpty());
    }

    @Test
    void haltIfFatalHaltsOnlyForErrors() {
        var halts = new ArrayList<Integer>();
        FatalErrors.haltIfFatal(new RuntimeException("transient"), halts::add);
        Assertions.assertTrue(halts.isEmpty());
        FatalErrors.haltIfFatal(new RuntimeException("x", new OutOfMemoryError("heap")), halts::add);
        Assertions.assertEquals(java.util.List.of(3), halts);
    }
}
