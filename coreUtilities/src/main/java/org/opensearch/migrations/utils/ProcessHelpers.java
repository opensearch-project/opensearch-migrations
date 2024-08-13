package org.opensearch.migrations.utils;

import java.util.UUID;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProcessHelpers {
    private static final String DEFAULT_NODE_ID = UUID.randomUUID().toString();

    public static String getNodeInstanceName() {
        String id = null;
        try {
            id = System.getenv("ECS_TASK_ID"); // for ECS deployments
            if (id != null) {
                return id;
            }
            id = System.getenv("HOSTNAME"); // for any kubernetes deployed pod
            if (id != null) {
                return id;
            }
            // add additional fallbacks here
            id = DEFAULT_NODE_ID;
            return id;
        } finally {
            if (id != null) {
                String finalId = id;
                log.atInfo().setMessage(() -> "getNodeInstanceName()=" + finalId).log();
            }
        }
    }
}
