package org.opensearch.migrations.bulkload.common;

import java.util.OptionalInt;
import java.util.function.IntConsumer;

/**
 * Fail-fast policy for {@link Error}: an Error anywhere in a cause chain means the process is in an
 * undefined state and must halt rather than let a retry or error-handling path swallow it. Reactive
 * pipelines deliver failures down the onError channel instead of up the stack, so without these
 * checks a retry operator treats an OutOfMemoryError like a transient network failure and thrashes.
 *
 * <p>The exit codes are part of the supervisor contract (runJavaWithClasspathWithRepeat.sh): an
 * OutOfMemoryError halts with {@value #OUT_OF_MEMORY_EXIT_CODE}, which the next worker's dirty-lease
 * sweep escalates to a higher memory tier; any other Error halts with {@value #FATAL_ERROR_EXIT_CODE},
 * which releases the lease for a same-tier retry — a non-memory crash must never climb the memory
 * ladder. Halting uses {@link Runtime#halt} rather than {@link System#exit}: shutdown hooks may
 * allocate or block, and neither is safe once an Error has been raised.
 */
public final class FatalErrors {
    public static final int OUT_OF_MEMORY_EXIT_CODE = 3;
    public static final int FATAL_ERROR_EXIT_CODE = 8;
    private static final int MAX_CAUSE_DEPTH = 32;

    private FatalErrors() {}

    /**
     * The exit code the chain calls for: {@value #OUT_OF_MEMORY_EXIT_CODE} when it holds an
     * OutOfMemoryError, {@value #FATAL_ERROR_EXIT_CODE} when it holds any other Error, empty when
     * it holds no Error at all.
     */
    public static OptionalInt exitCodeFor(Throwable throwable) {
        var sawError = false;
        var cursor = throwable;
        for (int depth = 0; cursor != null && depth < MAX_CAUSE_DEPTH; ++depth, cursor = cursor.getCause()) {
            if (cursor instanceof OutOfMemoryError) {
                return OptionalInt.of(OUT_OF_MEMORY_EXIT_CODE);
            }
            sawError |= cursor instanceof Error;
        }
        return sawError ? OptionalInt.of(FATAL_ERROR_EXIT_CODE) : OptionalInt.empty();
    }

    /** Halts the process when the chain holds an Error; otherwise returns and the caller proceeds. */
    public static void haltIfFatal(Throwable throwable) {
        haltIfFatal(throwable, code -> Runtime.getRuntime().halt(code));
    }

    static void haltIfFatal(Throwable throwable, IntConsumer halt) {
        exitCodeFor(throwable).ifPresent(code -> {
            // The logging system may itself be unable to allocate at this point; stderr only.
            System.err.println("Halting with exit " + code + " due to fatal error: " + throwable);
            throwable.printStackTrace(System.err);
            halt.accept(code);
        });
    }
}
