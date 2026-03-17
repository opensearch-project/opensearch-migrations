import {
    expr,
    INTERNAL,
    makeStringTypeProxy,
    selectInputsForRegister,
    typeToken,
    WorkflowBuilder
} from '@opensearch-migrations/argo-workflow-builders';
import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";
import {makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";
import {K8S_RESOURCE_RETRY_STRATEGY} from "./commonUtils/resourceRetryStrategy";

const SECONDS_IN_DAYS = 24 * 3600;
const LONGEST_POSSIBLE_MIGRATION = 365 * SECONDS_IN_DAYS;
const CRD_API_VERSION = "migrations.opensearch.org/v1alpha1";

export const ResourceManagement = WorkflowBuilder.create({
    k8sResourceName: "resource-management",
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)


    // ── Wait templates (resource get with retry) ─────────────────────────

    .addTemplate("waitForKafkaClusterCreated", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addWaitForNewResource(b => b
            .setDefinition({
                resourceKindAndName: expr.concat(expr.literal("kafka/"), b.inputs.resourceName),
                waitForCreation: {
                    kubectlImage: b.inputs.imageMigrationConsoleLocation,
                    kubectlImagePullPolicy: b.inputs.imageMigrationConsolePullPolicy,
                    maxDurationSeconds: LONGEST_POSSIBLE_MIGRATION
                }
            })
        )
    )


    .addTemplate("waitForKafkaClusterReady", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addWaitForExistingResource(b => b
            .setDefinition({
                resource: {
                    apiVersion: "kafka.strimzi.io/v1",
                    kind: "Kafka",
                    name: b.inputs.resourceName
                },
                conditions: {successCondition: "status.listeners"}
            })
        )
    )


    .addTemplate("waitForKafkaCluster", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addSteps(b => b
            .addStep("waitForCreation", INTERNAL, "waitForKafkaClusterCreated", c =>
                c.register({...selectInputsForRegister(b, c), resourceName: b.inputs.resourceName})
            )
            .addStep("waitForReady", INTERNAL, "waitForKafkaClusterReady", c =>
                c.register({...selectInputsForRegister(b, c), resourceName: b.inputs.resourceName})
            )
        )
    )


    .addTemplate("readKafkaConnectionProfile", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "get",
                manifest: {
                    apiVersion: "kafka.strimzi.io/v1",
                    kind: "Kafka",
                    metadata: {name: b.inputs.resourceName}
                }
            })
            .addJsonPathOutput("bootstrapServers", "{.status.listeners[0].bootstrapServers}", typeToken<string>())
            .addJsonPathOutput("listenerName", "{.status.listeners[0].name}", typeToken<string>())
            .addJsonPathOutput("authType", "{.spec.kafka.listeners[0].authentication.type}", typeToken<string>()))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )


    .addTemplate("waitForKafkaTopic", b => b
        .addRequiredInput("resourceName", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addWaitForNewResource(b => b
            .setDefinition({
                resourceKindAndName: expr.concat(expr.literal(""), b.inputs.resourceName),
                waitForCreation: {
                    kubectlImage: b.inputs.imageMigrationConsoleLocation,
                    kubectlImagePullPolicy: b.inputs.imageMigrationConsolePullPolicy,
                    maxDurationSeconds: LONGEST_POSSIBLE_MIGRATION
                }
            })
        )
    )


    .addTemplate("waitForCapturedTraffic", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addWaitForExistingResource(b => b
            .setDefinition({
                resource: {
                    apiVersion: CRD_API_VERSION,
                    kind: "CapturedTraffic",
                    name: b.inputs.resourceName
                },
                conditions: {successCondition: "status.phase == Ready"}
            })
        )
    )


    .addTemplate("waitForDataSnapshot", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addWaitForExistingResource(b => b
            .setDefinition({
                resource: {
                    apiVersion: CRD_API_VERSION,
                    kind: "DataSnapshot",
                    name: b.inputs.resourceName
                },
                conditions: {successCondition: "status.phase == Ready"}
            })
        )
    )


    .addTemplate("waitForSnapshotMigration", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addWaitForExistingResource(b => b
            .setDefinition({
                resource: {
                    apiVersion: CRD_API_VERSION,
                    kind: "SnapshotMigration",
                    name: b.inputs.resourceName
                },
                conditions: {successCondition: "status.phase == Ready"}
            })
        )
    )


    // ── CRD patch-to-ready templates ─────────────────────────────────────

    .addTemplate("patchCapturedTrafficReady", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "patch",
                flags: ["--type", "merge", "--subresource=status"],
                manifest: {
                    apiVersion: CRD_API_VERSION,
                    kind: "CapturedTraffic",
                    metadata: {name: b.inputs.resourceName},
                    status: {phase: "Ready"}
                }
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )


    .addTemplate("patchDataSnapshotReady", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addRequiredInput("snapshotName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "patch",
                flags: ["--type", "merge", "--subresource=status"],
                manifest: {
                    apiVersion: CRD_API_VERSION,
                    kind: "DataSnapshot",
                    metadata: {name: b.inputs.resourceName},
                    status: {phase: "Ready", snapshotName: b.inputs.snapshotName}
                }
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )


    .addTemplate("patchSnapshotMigrationReady", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "patch",
                flags: ["--type", "merge", "--subresource=status"],
                manifest: {
                    apiVersion: CRD_API_VERSION,
                    kind: "SnapshotMigration",
                    metadata: {name: b.inputs.resourceName},
                    status: {phase: "Ready"}
                }
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )


    .addTemplate("readDataSnapshotName", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "get",
                manifest: {
                    apiVersion: CRD_API_VERSION,
                    kind: "DataSnapshot",
                    metadata: {name: b.inputs.resourceName}
                }
            })
            .addJsonPathOutput("snapshotName", "{.status.snapshotName}", typeToken<string>()))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )


    // ── Generic phase patch template ────────────────────────────────────

    .addTemplate("patchResourcePhase", t => t
        .addRequiredInput("resourceKind", typeToken<string>())
        .addRequiredInput("resourceName", typeToken<string>())
        .addRequiredInput("phase", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "patch",
                flags: ["--type", "merge", "--subresource=status"],
                manifest: {
                    apiVersion: CRD_API_VERSION,
                    kind: makeStringTypeProxy(b.inputs.resourceKind),
                    metadata: { name: b.inputs.resourceName },
                    status: { phase: makeStringTypeProxy(b.inputs.phase) }
                }
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )


    // ── Workflow UID approval annotation patch ───────────────────────────

    .addTemplate("patchApprovalAnnotation", t => t
        .addRequiredInput("resourceApiVersion", typeToken<string>())
        .addRequiredInput("resourceKind", typeToken<string>())
        .addRequiredInput("resourceName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "patch",
                flags: ["--type", "merge"],
                manifest: {
                    apiVersion: makeStringTypeProxy(b.inputs.resourceApiVersion),
                    kind: makeStringTypeProxy(b.inputs.resourceKind),
                    metadata: {
                        name: b.inputs.resourceName,
                        annotations: {
                            "migrations.opensearch.org/approved-by-run":
                                makeStringTypeProxy(expr.getWorkflowValue("uid"))
                        }
                    }
                }
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )


    .getFullScope();
