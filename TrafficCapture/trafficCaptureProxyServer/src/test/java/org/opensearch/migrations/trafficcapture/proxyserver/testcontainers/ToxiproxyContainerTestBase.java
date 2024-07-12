package org.opensearch.migrations.trafficcapture.proxyserver.testcontainers;

import java.io.IOException;
import java.util.HashSet;
import java.util.concurrent.ConcurrentSkipListSet;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.ToxiproxyContainer;

public class ToxiproxyContainerTestBase extends TestContainerTestBase<ToxiproxyContainer> {

    private static final ToxiproxyContainer toxiproxy = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:latest")
        .withAccessToHost(true);

    final ConcurrentSkipListSet<Integer> toxiproxyUnusedExposedPorts = new ConcurrentSkipListSet<>();

    static int getListeningPort(Proxy proxy) {
        return Integer.parseInt(proxy.getListen().replaceAll(".*:", ""));
    }

    public ToxiproxyContainer getContainer() {
        return toxiproxy;
    }

    @Override
    public void start() {
        final int TOXIPROXY_CONTROL_PORT = 8474;
        getContainer().start();
        var concurrentPortSet = new HashSet<>(getContainer().getExposedPorts());
        concurrentPortSet.remove(TOXIPROXY_CONTROL_PORT);
        toxiproxyUnusedExposedPorts.addAll(concurrentPortSet);
    }

    @Override
    public void stop() {
        toxiproxyUnusedExposedPorts.clear();
        getContainer().stop();
    }

    public void deleteProxy(Proxy proxy) {
        var proxyPort = getListeningPort(proxy);
        try {
            proxy.delete();
            toxiproxyUnusedExposedPorts.add(proxyPort);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Proxy getProxy(GenericContainer<?> container) {
        var containerPort = container.getFirstMappedPort();
        final ToxiproxyClient toxiproxyClient = new ToxiproxyClient(
            toxiproxy.getHost(),
            getContainer().getControlPort()
        );
        org.testcontainers.Testcontainers.exposeHostPorts(containerPort);
        try {
            var containerName = (container.getDockerImageName()
                + "_"
                + container.getContainerName()
                + "_"
                + Thread.currentThread().getId()).replaceAll("[^a-zA-Z0-9_]+", "_");
            synchronized (toxiproxyUnusedExposedPorts) {
                var proxyPort = toxiproxyUnusedExposedPorts.first();
                var proxy = toxiproxyClient.createProxy(
                    containerName,
                    "0.0.0.0:" + proxyPort,
                    "host.testcontainers.internal" + ":" + containerPort
                );
                toxiproxyUnusedExposedPorts.remove(proxyPort);
                proxy.enable();
                return proxy;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String getProxyUrlHttp(Proxy proxy) {
        return "http://" + getContainer().getHost() + ":" + getContainer().getMappedPort(getListeningPort(proxy));
    }
}
