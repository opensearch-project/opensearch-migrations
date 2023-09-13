package org.opensearch.migrations.testutils;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;

public class NettyLeakCheckTestExtension implements InvocationInterceptor {
    public NettyLeakCheckTestExtension() {}

    private void wrapWithLeakChecks(ExtensionContext extensionContext, Callable repeatCall, Callable finalCall)
            throws Throwable {
        int repetitions = extensionContext.getTestMethod()
                .map(ec->ec.getAnnotation(WrapWithNettyLeakDetection.class).repetitions())
                .orElseThrow(() -> new IllegalStateException("No test method present"));
        CountingNettyResourceLeakDetector.activate();

        // Repeat calling your test logic directly
        for (int i = 0; i < repetitions; i++) {
            ((i==repetitions-1)?finalCall:repeatCall).call();
            System.gc();
            System.runFinalization();
        }

        Assertions.assertEquals(0, CountingNettyResourceLeakDetector.getNumLeaks());
    }

    @Override
    public void interceptTestMethod(InvocationInterceptor.Invocation<Void> invocation,
                                    ReflectiveInvocationContext<Method> invocationContext,
                                    ExtensionContext extensionContext) throws Throwable {

        var selfInstance =
                invocationContext.getTarget().orElseThrow(() -> new RuntimeException("Target instance not found"));
        wrapWithLeakChecks(extensionContext,
                ()->invocationContext.getExecutable().invoke(selfInstance),
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

    @Override
    public void interceptTestTemplateMethod(Invocation<Void> invocation,
                                            ReflectiveInvocationContext<Method> invocationContext,
                                            ExtensionContext extensionContext) throws Throwable {
        var selfInstance =
                invocationContext.getTarget().orElseThrow(() -> new RuntimeException("Target instance not found"));
        wrapWithLeakChecks(extensionContext, ()->invocationContext.getExecutable().invoke(selfInstance,
                        invocationContext.getArguments().toArray()),
                ()-> wrapProceed(invocation));
    }
}
