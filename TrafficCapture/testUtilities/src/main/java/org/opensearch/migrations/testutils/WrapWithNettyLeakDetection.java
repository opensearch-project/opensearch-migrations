package org.opensearch.migrations.testutils;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

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
}
