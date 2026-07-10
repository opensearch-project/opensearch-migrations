package org.opensearch.migrations.trafficcapture.proxyserver;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class CaptureProxyNumThreadsTest {

    @Test
    public void testNumThreadsDefaultsToZeroForAutoDetect() {
        var params = CaptureProxy.parseArgs(new String[]{
            "--destinationUri", "http://localhost:9200",
            "--listenPort", "80",
            "--noCapture"
        });
        Assertions.assertEquals(0, params.numThreads,
            "numThreads should default to 0 (auto-detect based on available processors)");
    }

    @Test
    public void testNumThreadsCanBeExplicitlySet() {
        var params = CaptureProxy.parseArgs(new String[]{
            "--destinationUri", "http://localhost:9200",
            "--listenPort", "80",
            "--noCapture",
            "--numThreads", "4"
        });
        Assertions.assertEquals(4, params.numThreads);
    }

    @Test
    public void testNumThreadsZeroMeansAutoDetect() {
        var params = CaptureProxy.parseArgs(new String[]{
            "--destinationUri", "http://localhost:9200",
            "--listenPort", "80",
            "--noCapture",
            "--numThreads", "0"
        });
        // 0 means Netty will use Runtime.getRuntime().availableProcessors() * 2
        Assertions.assertEquals(0, params.numThreads);
    }
}
