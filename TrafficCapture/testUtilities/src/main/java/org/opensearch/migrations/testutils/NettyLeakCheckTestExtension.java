package org.opensearch.migrations.testutils;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.Callable;

public class NettyLeakCheckTestExtension implements InvocationInterceptor {
    public NettyLeakCheckTestExtension() {}

    private void wrapWithLeakChecks(ExtensionContext extensionContext, Callable repeatCall, Callable finalCall)
            throws Throwable {
        if (getAnnotation(extensionContext).map(a -> a.disableLeakChecks()).orElse(false)) {
            finalCall.call();
            return;
        } else {
            CountingNettyResourceLeakDetector.activate();
            int repetitions = getAnnotation(extensionContext).map(a -> a.repetitions())
                    .orElseThrow(()->new IllegalStateException("No test method present"));

            for (int i = 0; i < repetitions; i++) {
                ((i == repetitions - 1) ? finalCall : repeatCall).call();
                System.gc();
                System.runFinalization();
            }

            Assertions.assertEquals(0, CountingNettyResourceLeakDetector.getNumLeaks());
        }
    }

    private static Optional<WrapWithNettyLeakDetection> getAnnotation(ExtensionContext extensionContext) {
        return extensionContext.getTestMethod()
                .flatMap(m -> Optional.ofNullable(m.getAnnotation(WrapWithNettyLeakDetection.class)))
                .or(() -> extensionContext.getTestClass()
                        .flatMap(m -> Optional.ofNullable(m.getAnnotation(WrapWithNettyLeakDetection.class))));
    }

    @Override
    public void interceptTestMethod(InvocationInterceptor.Invocation<Void> invocation,
                                    ReflectiveInvocationContext<Method> invocationContext,
                                    ExtensionContext extensionContext) throws Throwable {

        var selfInstance =
                invocationContext.getTarget().orElseThrow(() -> new RuntimeException("Target instance not found"));
        wrapWithLeakChecks(extensionContext,
                ()-> {
                    Method m = invocationContext.getExecutable();
                    m.setAccessible(true);
                    return m.invoke(selfInstance);
                },
                ()-> wrapProceed(invocation));
    }

    @Override
    public void interceptTestTemplateMethod(Invocation<Void> invocation,
                                            ReflectiveInvocationContext<Method> invocationContext,
                                            ExtensionContext extensionContext) throws Throwable {
        var selfInstance =
                invocationContext.getTarget().orElseThrow(() -> new RuntimeException("Target instance not found"));
        wrapWithLeakChecks(extensionContext,
                ()->{
                    {
                        Method m = invocationContext.getExecutable();
                        m.setAccessible(true);
                        return m.invoke(selfInstance, invocationContext.getArguments().toArray());
                    }
                        },
                ()-> wrapProceed(invocation));
    }

    private static Void wrapProceed(Invocation<Void> invocation) throws Exception {
        try {
            return invocation.proceed();
        } catch (Exception e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
}
