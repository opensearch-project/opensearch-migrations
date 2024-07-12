package org.opensearch.migrations.testutils;

import java.lang.management.ManagementFactory;
import javax.management.MBeanServer;

import com.sun.management.HotSpotDiagnosticMXBean;

public class HeapDumper {
    private HeapDumper() {}

    /**
     * Utility method to create an hmap file to show all of the objects within the JVM.
     *
     * @param fileName
     * @param live     only include live objects
     * @throws Exception
     */
    public static void dumpHeap(String fileName, boolean live) throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        var mxBean = ManagementFactory.newPlatformMXBeanProxy(
            server,
            "com.sun.management:type=HotSpotDiagnostic",
            HotSpotDiagnosticMXBean.class
        );
        mxBean.dumpHeap(fileName, live);
    }
}
