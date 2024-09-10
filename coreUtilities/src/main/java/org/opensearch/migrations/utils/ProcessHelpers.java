package org.opensearch.migrations.utils;

import java.util.Optional;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProcessHelpers {
    private static final String DEFAULT_NODE_ID = "generated_" + UUID.randomUUID().toString();

    public static String getNodeInstanceName() {
        var nodeId = Optional.of("ECS_TASK_ID").map(System::getenv)
            .or(() -> Optional.of("HOSTNAME").map(System::getenv))
            .orElse(DEFAULT_NODE_ID);
        log.atInfo().setMessage(() -> "getNodeInstanceName()=" + nodeId).log();
        return nodeId;
    }
}
