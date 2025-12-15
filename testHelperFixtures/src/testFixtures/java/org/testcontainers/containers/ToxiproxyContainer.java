package org.testcontainers.containers;

import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.utility.DockerImageName;

/**
 * Local copy of ToxiproxyContainer since org.testcontainers:toxiproxy is not published for 2.0.x.
 * See: https://github.com/testcontainers/testcontainers-java/issues/11354
 */
public class ToxiproxyContainer extends GenericContainer<ToxiproxyContainer> {

    private static final DockerImageName DEFAULT_IMAGE_NAME = DockerImageName.parse("shopify/toxiproxy");
    private static final DockerImageName GHCR_IMAGE_NAME = DockerImageName.parse("ghcr.io/shopify/toxiproxy");
    private static final int TOXIPROXY_CONTROL_PORT = 8474;
    private static final int FIRST_PROXIED_PORT = 8666;
    private static final int LAST_PROXIED_PORT = 8666 + 31;

    public ToxiproxyContainer(String dockerImageName) {
        this(DockerImageName.parse(dockerImageName));
    }

    public ToxiproxyContainer(final DockerImageName dockerImageName) {
        super(dockerImageName);
        dockerImageName.assertCompatibleWith(DEFAULT_IMAGE_NAME, GHCR_IMAGE_NAME);

        addExposedPorts(TOXIPROXY_CONTROL_PORT);
        setWaitStrategy(new HttpWaitStrategy().forPath("/version").forPort(TOXIPROXY_CONTROL_PORT));

        for (int i = FIRST_PROXIED_PORT; i <= LAST_PROXIED_PORT; i++) {
            addExposedPort(i);
        }
    }

    public int getControlPort() {
        return getMappedPort(TOXIPROXY_CONTROL_PORT);
    }
}
