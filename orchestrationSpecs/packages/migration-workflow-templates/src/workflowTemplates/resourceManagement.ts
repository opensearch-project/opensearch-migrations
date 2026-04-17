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
import {DEFAULT_RESOURCES} from "@opensearch-migrations/schemas";

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
        .addRequiredInput("configChecksum", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addWaitForExistingResource(b => b
            .setDefinition({
                resource: {
                    apiVersion: "kafka.strimzi.io/v1",
                    kind: "Kafka",
                    name: b.inputs.resourceName
                },
                conditions: {
                    successCondition: expr.concat(
                        expr.literal("status.listeners, metadata.annotations.migration-configChecksum == "),
                        b.inputs.configChecksum
                    )
                }
            })
            .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
        )
    )


    .addTemplate("waitForKafkaCluster", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addRequiredInput("configChecksum", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addSteps(b => b
            .addStep("waitForCreation", INTERNAL, "waitForKafkaClusterCreated", c =>
                c.register({...selectInputsForRegister(b, c), resourceName: b.inputs.resourceName})
            )
            .addStep("waitForReady", INTERNAL, "waitForKafkaClusterReady", c =>
                c.register({...selectInputsForRegister(b, c), resourceName: b.inputs.resourceName, configChecksum: b.inputs.configChecksum})
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


    .addTemplate("checkKafkaExists", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addContainer(c => c
            .addImageInfo(c.inputs.imageMigrationConsoleLocation, c.inputs.imageMigrationConsolePullPolicy)
            .addCommand(["/bin/sh", "-c"])
            .addResources(DEFAULT_RESOURCES.SHELL_MIGRATION_CONSOLE_CLI)
            .addArgs([expr.fillTemplate(
                `set -e
RESOURCE_NAME='{{resourceName}}'
OUTPUT=$(kubectl get kafka "$RESOURCE_NAME" -o json 2>&1) && RC=0 || RC=$?
if [ $RC -eq 0 ]; then
  BOOTSTRAP=$(echo "$OUTPUT" | jq -r '.status.listeners[0].bootstrapServers // ""')
  if [ -n "$BOOTSTRAP" ]; then
    echo -n "true" > /tmp/exists.txt
    echo -n "$BOOTSTRAP" > /tmp/bootstrap.txt
    echo "$OUTPUT" | jq -r '.status.listeners[0].name // ""' | tr -d '\\n' > /tmp/listener.txt
    echo "$OUTPUT" | jq -r '.spec.kafka.listeners[0].authentication.type // ""' | tr -d '\\n' > /tmp/authtype.txt
  else
    echo -n "false" > /tmp/exists.txt
    echo -n "" > /tmp/bootstrap.txt
    echo -n "" > /tmp/listener.txt
    echo -n "" > /tmp/authtype.txt
  fi
  echo -n "Succeeded" > /tmp/phase.txt
elif echo "$OUTPUT" | grep -q "NotFound"; then
  echo -n "false" > /tmp/exists.txt
  echo -n "" > /tmp/bootstrap.txt
  echo -n "" > /tmp/listener.txt
  echo -n "" > /tmp/authtype.txt
  echo -n "Checked" > /tmp/phase.txt
else
  echo "kubectl get kafka failed: $OUTPUT" >&2
  exit 1
fi`,
                { "resourceName": c.inputs.resourceName }
            )])
        )
        .addPathOutput("exists", "/tmp/exists.txt", typeToken<string>())
        .addPathOutput("bootstrapServers", "/tmp/bootstrap.txt", typeToken<string>())
        .addPathOutput("listenerName", "/tmp/listener.txt", typeToken<string>())
        .addPathOutput("authType", "/tmp/authtype.txt", typeToken<string>())
        .addPathOutput("overriddenPhase", "/tmp/phase.txt", typeToken<string>())
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
        .addRequiredInput("configChecksum", typeToken<string>())
        .addRequiredInput("checksumField", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addWaitForExistingResource(b => b
            .setDefinition({
                resource: {
                    apiVersion: CRD_API_VERSION,
                    kind: "CapturedTraffic",
                    name: b.inputs.resourceName
                },
                conditions: {
                    successCondition: expr.concat(
                        expr.literal("status.phase == Ready, status."),
                        b.inputs.checksumField,
                        expr.literal(" == "),
                        b.inputs.configChecksum
                    ),
                    failureCondition: "status.phase == Error"
                }
            })
            .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
        )
    )


    .addTemplate("waitForDataSnapshot", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addRequiredInput("configChecksum", typeToken<string>())
        .addRequiredInput("checksumField", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addWaitForExistingResource(b => b
            .setDefinition({
                resource: {
                    apiVersion: CRD_API_VERSION,
                    kind: "DataSnapshot",
                    name: b.inputs.resourceName
                },
                conditions: {
                    successCondition: expr.concat(
                        expr.literal("status.phase == Completed, status."),
                        b.inputs.checksumField,
                        expr.literal(" == "),
                        b.inputs.configChecksum
                    ),
                    failureCondition: "status.phase == Error"
                }
            })
            .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
        )
    )


    .addTemplate("waitForSnapshotMigration", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addRequiredInput("configChecksum", typeToken<string>())
        .addRequiredInput("checksumField", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addWaitForExistingResource(b => b
            .setDefinition({
                resource: {
                    apiVersion: CRD_API_VERSION,
                    kind: "SnapshotMigration",
                    name: b.inputs.resourceName
                },
                conditions: {
                    successCondition: expr.concat(
                        expr.literal("status.phase == Completed, status."),
                        b.inputs.checksumField,
                        expr.literal(" == "),
                        b.inputs.configChecksum
                    ),
                    failureCondition: "status.phase == Error"
                }
            })
            .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
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
        .addRequiredInput("configChecksum", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "patch",
                flags: ["--type", "merge", "--subresource=status"],
                manifest: {
                    apiVersion: CRD_API_VERSION,
                    kind: "DataSnapshot",
                    metadata: {name: b.inputs.resourceName},
                    status: {
                        phase: "Completed",
                        snapshotName: b.inputs.snapshotName,
                        configChecksum: makeStringTypeProxy(b.inputs.configChecksum),
                        checksumForSnapshotMigration: makeStringTypeProxy(b.inputs.configChecksum),
                    }
                }
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )


    .addTemplate("patchSnapshotMigrationReady", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addRequiredInput("configChecksum", typeToken<string>())
        .addRequiredInput("checksumForReplayer", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "patch",
                flags: ["--type", "merge", "--subresource=status"],
                manifest: {
                    apiVersion: CRD_API_VERSION,
                    kind: "SnapshotMigration",
                    metadata: {name: b.inputs.resourceName},
                    status: {
                        phase: "Completed",
                        configChecksum: makeStringTypeProxy(b.inputs.configChecksum),
                        checksumForReplayer: makeStringTypeProxy(b.inputs.checksumForReplayer),
                    }
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

    .addTemplate("readResourcePhase", t => t
        .addRequiredInput("resourceKind", typeToken<string>())
        .addRequiredInput("resourceName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "get",
                manifest: {
                    apiVersion: CRD_API_VERSION,
                    kind: makeStringTypeProxy(b.inputs.resourceKind),
                    metadata: { name: b.inputs.resourceName }
                }
            })
            .addJsonPathOutput("phase", "{.status.phase}", typeToken<string>())
            .addJsonPathOutput("configChecksum", "{.status.configChecksum}", typeToken<string>()))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )

    .addTemplate("patchResourcePhase", t => t
        .addRequiredInput("resourceKind", typeToken<string>())
        .addRequiredInput("resourceName", typeToken<string>())
        .addRequiredInput("phase", typeToken<string>())
        .addOptionalInput("configChecksum", c => "")
        .addOptionalInput("checksumForSnapshot", c => "")
        .addOptionalInput("checksumForReplayer", c => "")
        .addOptionalInput("checksumForSnapshotMigration", c => "")
        .addResourceTask(b => b
            .setDefinition({
                action: "patch",
                flags: ["--type", "merge", "--subresource=status"],
                manifest: {
                    apiVersion: CRD_API_VERSION,
                    kind: makeStringTypeProxy(b.inputs.resourceKind),
                    metadata: { name: b.inputs.resourceName },
                    status: {
                        phase: makeStringTypeProxy(b.inputs.phase),
                        configChecksum: makeStringTypeProxy(b.inputs.configChecksum),
                        checksumForSnapshot: makeStringTypeProxy(b.inputs.checksumForSnapshot),
                        checksumForReplayer: makeStringTypeProxy(b.inputs.checksumForReplayer),
                        checksumForSnapshotMigration: makeStringTypeProxy(b.inputs.checksumForSnapshotMigration),
                    }
                }
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )


    // ── Config checksum annotation patch (for resources we don't own) ────

    .addTemplate("patchConfigChecksumAnnotation", t => t
        .addRequiredInput("resourceApiVersion", typeToken<string>())
        .addRequiredInput("resourceKind", typeToken<string>())
        .addRequiredInput("resourceName", typeToken<string>())
        .addRequiredInput("configChecksum", typeToken<string>())
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
                            "migration-configChecksum": makeStringTypeProxy(b.inputs.configChecksum)
                        }
                    }
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
