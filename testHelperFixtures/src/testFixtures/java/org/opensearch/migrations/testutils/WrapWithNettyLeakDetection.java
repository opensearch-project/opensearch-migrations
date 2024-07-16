package org.opensearch.migrations.testutils;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(NettyLeakCheckTestExtension.class)
public @interface WrapWithNettyLeakDetection {
    /**
     * Some leaks might need to put a bit more stress on the GC for objects to get cleared out
     * and trigger potential checks within any resource finalizers to determine if there have
     * been leaks.  This could also be used to make leaks more obvious as the test environment
     * itself will have many resources and looking for just one rogue ByteBufHolder in an hmap
     * file could be difficult.
     * @return
     */
    int repetitions() default 16;

    /**
     * Set this to true to disable running any netty leak checks.  This will cause the test to be
     * run by itself, once, without any extra overhead.
     */
    boolean disableLeakChecks() default false;
}
