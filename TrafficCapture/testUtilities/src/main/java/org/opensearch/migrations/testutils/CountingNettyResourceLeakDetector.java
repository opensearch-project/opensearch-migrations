package org.opensearch.migrations.testutils;

import com.sun.management.HotSpotDiagnosticMXBean;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.ResourceLeakDetectorFactory;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.extern.slf4j.Slf4j;

import javax.management.MBeanServer;
import java.lang.management.ManagementFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class doesn't do too much over the stock ResourceLeakDetector, but it does help
 * to provide a counter on the number of leaks detected, which can then be checked within
 * automated tests.
 */
@Slf4j
public class CountingNettyResourceLeakDetector<T> extends ResourceLeakDetector<T> {

    private static AtomicInteger numLeaksFoundAtomic = new AtomicInteger();

    /**
     * Utility method to create an hmap file to show all of the objects within the JVM.
     * @param fileName
     * @param live only include live objects
     * @throws Exception
     */
    public static void dumpHeap(String fileName, boolean live) throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        var mxBean = ManagementFactory.newPlatformMXBeanProxy(
                server, "com.sun.management:type=HotSpotDiagnostic", HotSpotDiagnosticMXBean.class);
        mxBean.dumpHeap(fileName, live);
    }

    /**
     * Do everything necessary to turn leak detection on with the highest sensitivity.
     */
    public static void activate() {
        ResourceLeakDetectorFactory.setResourceLeakDetectorFactory(new CountingNettyResourceLeakDetector.MyResourceLeakDetectorFactory());
        CountingNettyResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
        numLeaksFoundAtomic.set(0);
    }

    public static class MyResourceLeakDetectorFactory extends ResourceLeakDetectorFactory {
        static {
            System.setProperty("io.netty.leakDetection.targetRecords", "32");
            System.setProperty("io.netty.leakDetection.samplingInterval", "1");
            System.setProperty("io.netty.leakDetection.level", "paranoid");
        }

        @Override
        public <T> ResourceLeakDetector<T> newResourceLeakDetector(Class<T> resource, int samplingInterval, long maxActive) {
            return new CountingNettyResourceLeakDetector<>(resource, samplingInterval);
        }
    }

    public CountingNettyResourceLeakDetector(Class<?> resourceType, int samplingInterval) {
        super(resourceType, 1);
    }

    @Override
    protected void reportTracedLeak(String resourceType, String records) {
        incrementLeakCount();
        super.reportTracedLeak(resourceType, records);
    }

    @Override
    protected void reportUntracedLeak(String resourceType) {
        incrementLeakCount();
        super.reportUntracedLeak(resourceType);
    }

    public static int getNumLeaks() {
        return numLeaksFoundAtomic.get();
    }

    private static void incrementLeakCount() {
        if (numLeaksFoundAtomic.incrementAndGet() == 1) {
            setupMonitoringLogger();
        }
    }

    private static void setupMonitoringLogger() {
        var eventExecutor = new NioEventLoopGroup(1, new DefaultThreadFactory("leakMonitor"));
        eventExecutor.scheduleAtFixedRate(()->{
            System.gc();
            System.runFinalization();
            var numLeaks = numLeaksFoundAtomic.get();
            if (numLeaks > 0) {
                log.warn("numLeaks=" + CountingNettyResourceLeakDetector.getNumLeaks());
            }
        }, 0, 1000, TimeUnit.MILLISECONDS);
    }
}
