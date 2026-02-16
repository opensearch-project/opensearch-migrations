import {
    BaseExpression,
    expr,
    INTERNAL,
    makeStringTypeProxy,
    typeToken,
    WorkflowBuilder
} from "@opensearch-migrations/argo-workflow-builders";
import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";

export function getRfsCoordinatorClusterName(sessionName: BaseExpression<string>): BaseExpression<string> {
    return expr.concat(sessionName, expr.literal("-rfs-coordinator"));
}

function createRfsCoordinatorSecretManifest(clusterName: BaseExpression<string>) {
    return {
        apiVersion: "v1",
        kind: "Secret",
        metadata: {
            name: makeStringTypeProxy(expr.concat(clusterName, expr.literal("-creds"))),
            labels: {
                app: makeStringTypeProxy(clusterName)
            }
        },
        type: "Opaque",
        stringData: {
            username: "admin",
            password: "myStrongPassword123!"
        }
    };
}

function createRfsCoordinatorServiceManifest(clusterName: BaseExpression<string>) {
    return {
        apiVersion: "v1",
        kind: "Service",
        metadata: {
            name: makeStringTypeProxy(clusterName),
            labels: {
                app: makeStringTypeProxy(clusterName)
            }
        },
        spec: {
            clusterIP: "None", // Headless service for direct pod communication in StatefulSet
            selector: {
                app: makeStringTypeProxy(clusterName)
            },
            ports: [
                {
                    name: "https",
                    port: 9200,
                    targetPort: "https"
                }
            ]
        }
    };
}

function createRfsCoordinatorStatefulSetManifest(clusterName: BaseExpression<string>) {
    return {
        apiVersion: "apps/v1",
        kind: "StatefulSet",
        metadata: {
            name: makeStringTypeProxy(clusterName),
            labels: {
                app: makeStringTypeProxy(clusterName),
                "app.kubernetes.io/instance": makeStringTypeProxy(clusterName)
            }
        },
        spec: {
            serviceName: makeStringTypeProxy(clusterName),
            replicas: 1,
            persistentVolumeClaimRetentionPolicy: {
                whenDeleted: "Delete",
                whenScaled: "Retain"
            },
            selector: {
                matchLabels: {
                    app: makeStringTypeProxy(clusterName)
                }
            },
            template: {
                metadata: {
                    labels: {
                        app: makeStringTypeProxy(clusterName),
                        "app.kubernetes.io/instance": makeStringTypeProxy(clusterName)
                    }
                },
                spec: {
                    serviceAccountName: "argo-workflow-executor",
                    securityContext: {
                        fsGroup: 1000
                    },
                    containers: [
                        {
                            name: "opensearch",
                            image: "opensearchproject/opensearch:3.1.0",
                            ports: [
                                {
                                    name: "https",
                                    containerPort: 9200
                                }
                            ],
                            env: [
                                {
                                    name: "cluster.name",
                                    value: makeStringTypeProxy(clusterName)
                                },
                                {
                                    name: "discovery.type",
                                    value: "single-node"
                                },
                                {
                                    name: "OPENSEARCH_INITIAL_ADMIN_USERNAME",
                                    valueFrom: {
                                        secretKeyRef: {
                                            name: makeStringTypeProxy(expr.concat(clusterName, expr.literal("-creds"))),
                                            key: "username",
                                            optional: false
                                        }
                                    }
                                },
                                {
                                    name: "OPENSEARCH_INITIAL_ADMIN_PASSWORD",
                                    valueFrom: {
                                        secretKeyRef: {
                                            name: makeStringTypeProxy(expr.concat(clusterName, expr.literal("-creds"))),
                                            key: "password",
                                            optional: false
                                        }
                                    }
                                },
                                {
                                    name: "OPENSEARCH_JAVA_OPTS",
                                    value: "-Xms1g -Xmx1g"
                                }
                            ],
                            resources: {
                                requests: {
                                    cpu: "1",
                                    memory: "2Gi"
                                },
                                limits: {
                                    cpu: "1",
                                    memory: "2Gi"
                                }
                            },
                            readinessProbe: {
                                exec: {
                                    command: [
                                        "sh",
                                        "-c",
                                        "curl -sk -u \"${OPENSEARCH_INITIAL_ADMIN_USERNAME}:${OPENSEARCH_INITIAL_ADMIN_PASSWORD}\" \"https://localhost:9200/_cluster/health?wait_for_status=yellow&timeout=10s\""
                                    ]
                                },
                                // Waits and gives JVM time to start before probing
                                initialDelaySeconds: 10,
                                // Check every 15s; matched with timeoutSeconds to prevent overlapping probes
                                periodSeconds: 15,
                                // Must be >= the curl internal timeout (10s) to allow the API to respond
                                timeoutSeconds: 15,
                                // 20 failures * 15s = 300s (5m). Provides a 5min window for the
                                // single-node cluster to reach 'yellow' status during bootstrap.
                                failureThreshold: 20
                            },
                            volumeMounts: [
                                {
                                    name: "data",
                                    mountPath: "/usr/share/opensearch/data"
                                }
                            ]
                        }
                    ]
                }
            },
            volumeClaimTemplates: [
                {
                    metadata: {
                        name: "data"
                    },
                    spec: {
                        accessModes: ["ReadWriteOnce"],
                        resources: {
                            requests: {
                                storage: "1Gi"
                            }
                        }
                    }
                }
            ]
        }
    };
}

export function makeRfsCoordinatorConfig(clusterName: BaseExpression<string>) {
    return expr.makeDict({
        endpoint: expr.concat(expr.literal("https://"), clusterName, expr.literal(":9200")),
        label: clusterName,
        allowInsecure: expr.literal(true),
        authConfig: expr.makeDict({
            basic: expr.makeDict({
                secretName: expr.concat(clusterName, expr.literal("-creds"))
            })
        })
    });
}

export const RfsCoordinatorCluster = WorkflowBuilder.create({
    k8sResourceName: "rfs-coordinator-cluster",
    serviceAccountName: "argo-workflow-executor"
})
    .addParams(CommonWorkflowParameters)

    .addTemplate("createRfsCoordinatorSecret", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: true,
                manifest: createRfsCoordinatorSecretManifest(b.inputs.clusterName)
            }))
    )

    .addTemplate("createRfsCoordinatorService", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: true,
                manifest: createRfsCoordinatorServiceManifest(b.inputs.clusterName)
            }))
    )

    .addTemplate("createRfsCoordinatorStatefulSet", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: true,
                successCondition: "status.readyReplicas > 0",
                manifest: createRfsCoordinatorStatefulSetManifest(b.inputs.clusterName)
            }))
    )

    .addTemplate("createRfsCoordinator", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addOptionalInput("groupName", c => "Start RFS OpenSearch cluster for worker coordination")
        .addSteps(b => b
            .addStep("createSecret", INTERNAL, "createRfsCoordinatorSecret", c =>
                c.register({ clusterName: b.inputs.clusterName }))
            .addStepGroup(g => g
                .addStep("createService", INTERNAL, "createRfsCoordinatorService", c =>
                    c.register({ clusterName: b.inputs.clusterName }))
                .addStep("createStatefulSet", INTERNAL, "createRfsCoordinatorStatefulSet", c =>
                    c.register({ clusterName: b.inputs.clusterName }))
            )
        )
    )

    .addTemplate("deleteRfsCoordinatorStatefulSet", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "delete",
                flags: ["--ignore-not-found"],
                manifest: {
                    apiVersion: "apps/v1",
                    kind: "StatefulSet",
                    metadata: {
                        name: makeStringTypeProxy(b.inputs.clusterName)
                    }
                }
            }))
    )

    .addTemplate("deleteRfsCoordinatorService", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "delete",
                flags: ["--ignore-not-found"],
                manifest: {
                    apiVersion: "v1",
                    kind: "Service",
                    metadata: {
                        name: makeStringTypeProxy(b.inputs.clusterName)
                    }
                }
            }))
    )

    .addTemplate("deleteRfsCoordinatorSecret", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "delete",
                flags: ["--ignore-not-found"],
                manifest: {
                    apiVersion: "v1",
                    kind: "Secret",
                    metadata: {
                        name: makeStringTypeProxy(expr.concat(b.inputs.clusterName, expr.literal("-creds")))
                    }
                }
            }))
    )

    .addTemplate("deleteRfsCoordinator", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addOptionalInput("groupName", c => "Stop RFS OpenSearch cluster for worker coordination")
        .addSteps(b => b
            .addStepGroup(g => g
                .addStep("deleteStatefulSet", INTERNAL, "deleteRfsCoordinatorStatefulSet", c =>
                    c.register({ clusterName: b.inputs.clusterName }))
                .addStep("deleteService", INTERNAL, "deleteRfsCoordinatorService", c =>
                    c.register({ clusterName: b.inputs.clusterName }))
            )
            .addStepGroup(g => g
                .addStep("deleteSecret", INTERNAL, "deleteRfsCoordinatorSecret", c =>
                    c.register({ clusterName: b.inputs.clusterName }))
            )
        )
    )

    .getFullScope();
