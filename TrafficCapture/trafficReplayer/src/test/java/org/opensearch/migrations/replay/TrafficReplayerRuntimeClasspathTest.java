package org.opensearch.migrations.replay;

import java.io.File;
import java.net.URLClassLoader;
import java.util.Arrays;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TrafficReplayerRuntimeClasspathTest {
    @Test
    void shippingRuntimeClasspathCanInitializeTheMainClass() throws Exception {
        var classpath = System.getProperty("trafficReplayerMainRuntimeClasspath");
        Assertions.assertNotNull(classpath);
        var urls = Arrays.stream(classpath.split(File.pathSeparator))
            .map(File::new)
            .map(file -> {
                try {
                    return file.toURI().toURL();
                } catch (Exception e) {
                    throw new IllegalArgumentException(e);
                }
            })
            .toArray(java.net.URL[]::new);

        try (var loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            Assertions.assertDoesNotThrow(
                () -> Class.forName("com.lmax.disruptor.EventTranslatorVararg", false, loader)
            );
            Assertions.assertDoesNotThrow(
                () -> Class.forName("software.amazon.msk.auth.iam.IAMLoginModule", false, loader)
            );
            Assertions.assertDoesNotThrow(
                () -> Class.forName("software.amazon.msk.auth.iam.IAMClientCallbackHandler", false, loader)
            );
            Assertions.assertDoesNotThrow(
                () -> Class.forName(TrafficReplayer.class.getName(), true, loader)
            );
        }
    }
}
