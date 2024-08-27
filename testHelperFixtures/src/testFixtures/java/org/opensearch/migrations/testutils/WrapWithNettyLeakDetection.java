package org.opensearch.migrations.testutils;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * This annotation causes a test to be run within the NettyLeakCheckTestExtension wrapper.
 * That will run a test multiple times with a CountingNettyResourceLeakDetector set as the
 * ByteBuf allocator to detect memory leaks.<br><br>
 *
 * Some leaks might need to put a bit more stress on the GC for objects to get cleared out
 * and trigger potential checks within any resource finalizers to determine if there have
 * been leaks.  This could also be used to make leaks more obvious as the test environment
 * itself will have many resources and looking for just one rogue ByteBufHolder in the hmap
 * file could be difficult.<br><br>
 *
 * In case min/max values for repetitions and runtime contradict each other, the test will
 * run enough times to meet the minimum requirements even if the max repetitions or runtime
 * is surpassed.
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(NettyLeakCheckTestExtension.class)
public @interface WrapWithNettyLeakDetection {
    /**
     * How many repetitions the test should run, provided that it hasn't gone over the maxRuntime (if specified)
     * and has run enough times to meet the minRuntime (if specified)
     */
    int repetitions() default -1;

    /**
     * Like repetitions this is a guesstimate to be provided to make sure that a test will
     * put enough ByteBuf pressure and activity to trigger exceptions and be useful in dumps.
     * This may take precedence over a repetitions value that is otherwise too small.
     */
    long minRuntimeMillis() default -1;
    /**
     * Like repetitions this is a guesstimate to be provided to make sure that a test will
     * put enough ByteBuf pressure and activity to trigger exceptions and be useful in dumps.
     * This may take precedence over a repetitions value that is too large.
     */
    long maxRuntimeMillis() default -1;

    /**
     * Set this to true to disable running any netty leak checks.  This will cause the test to be
     * run by itself, once, without any extra overhead.
     */
    boolean disableLeakChecks() default false;
}
