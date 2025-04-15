package org.opensearch.migrations.utils;

import java.util.Optional;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProcessHelpers {
    private static final String ID_UNIQUE_SUFFIX = String.valueOf(UUID.randomUUID());
    private static final String DEFAULT_NODE_BASE_ID = "generated_";

    private ProcessHelpers() {}

    public static String getNodeInstanceName() {
        var nodeId = Optional.of("HOSTNAME").map(System::getenv)
            .orElse(DEFAULT_NODE_BASE_ID)
            + "_" + ID_UNIQUE_SUFFIX;
        log.atInfo().setMessage("getNodeInstanceName()={}").addArgument(nodeId).log();
        return nodeId;
    }
}
