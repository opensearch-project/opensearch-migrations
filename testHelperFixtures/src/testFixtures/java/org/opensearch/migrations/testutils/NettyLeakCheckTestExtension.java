package org.opensearch.migrations.testutils;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.Callable;

import lombok.Lombok;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

public class NettyLeakCheckTestExtension implements InvocationInterceptor {
    public static final int DEFAULT_NUM_REPETITIONS = 16;
    private final boolean allLeakChecksAreDisabled;

    public NettyLeakCheckTestExtension() {
        allLeakChecksAreDisabled = System.getProperty("disableMemoryLeakTests", "").equalsIgnoreCase("true");
    }

    private <T> void wrapWithLeakChecks(
        ExtensionContext extensionContext,
        Callable<T> repeatCall,
        Callable<T> finalCall
    ) throws Throwable {
        if (allLeakChecksAreDisabled || getAnnotation(extensionContext).map(WrapWithNettyLeakDetection::disableLeakChecks).orElse(false)) {
            CountingNettyResourceLeakDetector.deactivate();
            finalCall.call();
            return;
        } else {
            CountingNettyResourceLeakDetector.activate();
            var repetitions = getAnnotation(extensionContext).map(WrapWithNettyLeakDetection::repetitions)
                .orElseThrow(() -> new IllegalStateException("No test method present"));
            var minRuntimeMs = getAnnotation(extensionContext).map(WrapWithNettyLeakDetection::minRuntimeMillis)
                .orElseThrow(() -> new IllegalStateException("No test method present"));
            var maxRuntimeMs = getAnnotation(extensionContext).map(WrapWithNettyLeakDetection::maxRuntimeMillis)
                .orElseThrow(() -> new IllegalStateException("No test method present"));
            if (repetitions == -1 &&
                minRuntimeMs == -1 &&
                maxRuntimeMs == -1) {
                repetitions = DEFAULT_NUM_REPETITIONS;
            }
            assert minRuntimeMs <= 0 || maxRuntimeMs <= 0 || minRuntimeMs <= maxRuntimeMs :
                "expected maxRuntime to be >= minRuntime";

            long nanosSpent = 0;
            for (int runNumber = 1; ; runNumber++) {
                var startTimeNanos = System.nanoTime();
                boolean lastRun = false;
                {
                    var timeSpentMs = nanosSpent / (1000 * 1000);
                    if (repetitions >= 0) {
                        lastRun = runNumber >= repetitions;
                    }
                    if (minRuntimeMs > 0) {
                        lastRun = timeSpentMs >= minRuntimeMs;
                    }
                    if (maxRuntimeMs > 0 && !lastRun) {
                        lastRun = timeSpentMs >= maxRuntimeMs;
                    }
                }
                (lastRun ? finalCall : repeatCall).call();
                nanosSpent += (System.nanoTime() - startTimeNanos);
                System.gc();
                System.runFinalization();
                if (lastRun) {
                    break;
                }
            }

            Assertions.assertEquals(0, CountingNettyResourceLeakDetector.getNumLeaks(),
                "Expected 0 leaks but got " + CountingNettyResourceLeakDetector.getNumLeaks());
        }
    }

    private static Optional<WrapWithNettyLeakDetection> getAnnotation(ExtensionContext extensionContext) {
        return extensionContext.getTestMethod()
            .flatMap(m -> Optional.ofNullable(m.getAnnotation(WrapWithNettyLeakDetection.class)))
            .or(
                () -> extensionContext.getTestClass()
                    .flatMap(m -> Optional.ofNullable(m.getAnnotation(WrapWithNettyLeakDetection.class)))
            );
    }

    @Override
    public void interceptTestMethod(
        InvocationInterceptor.Invocation<Void> invocation,
        ReflectiveInvocationContext<Method> invocationContext,
        ExtensionContext extensionContext
    ) throws Throwable {

        var selfInstance = invocationContext.getTarget()
            .orElseThrow(() -> new IllegalStateException("Target instance not found"));
        wrapWithLeakChecks(extensionContext, () -> {
            Method m = invocationContext.getExecutable();
            m.setAccessible(true);
            return m.invoke(selfInstance);
        }, () -> wrapProceed(invocation));
    }

    @Override
    public void interceptTestTemplateMethod(
        Invocation<Void> invocation,
        ReflectiveInvocationContext<Method> invocationContext,
        ExtensionContext extensionContext
    ) throws Throwable {
        var selfInstance = invocationContext.getTarget()
            .orElseThrow(() -> new IllegalStateException("Target instance not found"));
        wrapWithLeakChecks(extensionContext, () -> {
            Method m = invocationContext.getExecutable();
            m.setAccessible(true);
            return m.invoke(selfInstance, invocationContext.getArguments().toArray());
        }, () -> wrapProceed(invocation));
    }

    private static Void wrapProceed(Invocation<Void> invocation) throws Exception {
        try {
            return invocation.proceed();
        } catch (Exception e) {
            throw e;
        } catch (Throwable t) {
            throw Lombok.sneakyThrow(t);
        }
    }
}
