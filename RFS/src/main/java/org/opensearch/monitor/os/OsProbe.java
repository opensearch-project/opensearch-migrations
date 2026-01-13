/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Minimal stub to satisfy KNN codec class loading.
 */
package org.opensearch.monitor.os;

public class OsProbe {
    private static final OsProbe INSTANCE = new OsProbe();
    
    public static OsProbe getInstance() {
        return INSTANCE;
    }
    
    public long getTotalPhysicalMemorySize() {
        return Runtime.getRuntime().maxMemory();
    }
}
