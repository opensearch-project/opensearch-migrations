import {z} from "zod";
import {
    CLUSTER_VERSION_STRING,
    COMPLETE_SNAPSHOT_CONFIG,
    CONSOLE_SERVICES_CONFIG_FILE,
    NAMED_TARGET_CLUSTER_CONFIG,
    ResourceRequirementsType,
    ARGO_RFS_OPTIONS,
    ARGO_RFS_WORKFLOW_OPTION_KEYS,
    SNAPSHOT_MIGRATION_CONFIG,
} from "@opensearch-migrations/schemas";
import {MigrationConsole} from "./migrationConsole";
import {CONTAINER_NAMES} from "../containerNames";

import {
    AllowLiteralOrExpression,
    BaseExpression,
    expr,
    IMAGE_PULL_POLICY,
    INTERNAL, makeDirectTypeProxy, makeStringTypeProxy,
    selectInputsForRegister,
    Serialized,
    typeToken,
    WorkflowBuilder
} from "@opensearch-migrations/argo-workflow-builders";
import {Deployment} from "@opensearch-migrations/argo-workflow-builders";
import {OwnerReference} from "@opensearch-migrations/k8s-types";
import {makeRepoParamDict} from "./metadataMigration";
import {
    setupLog4jConfigForContainer,
    setupTestCredsForContainer
} from "./commonUtils/containerFragments";
import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";
import {makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";
import {makeTargetParamDict, makeRfsCoordinatorParamDict} from "./commonUtils/clusterSettingManipulators";
import {getHttpAuthSecretName} from "./commonUtils/clusterSettingManipulators";
import {getTargetHttpAuthCredsEnvVars, getCoordinatorHttpAuthCredsEnvVars} from "./commonUtils/basicCredsGetters";
import {K8S_RESOURCE_RETRY_STRATEGY} from "./commonUtils/resourceRetryStrategy";
import {RfsCoordinatorCluster, getRfsCoordinatorClusterName, makeRfsCoordinatorConfig} from "./rfsCoordinatorCluster";
import {ResourceManagement} from "./resourceManagement";

function shouldCreateRfsWorkCoordinationCluster(
    documentBackfillConfig: BaseExpression<Serialized<z.infer<typeof ARGO_RFS_OPTIONS>>>
): BaseExpression<boolean, "complicatedExpression"> {
    return expr.not(
        expr.get(
            expr.deserializeRecord(documentBackfillConfig),
            "useTargetClusterForWorkCoordination"
        )
    );
}

function makeParamsDict(
    sourceVersion: BaseExpression<z.infer<typeof CLUSTER_VERSION_STRING>>,
    targetConfig: BaseExpression<Serialized<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>>,
    rfsCoordinatorConfig: BaseExpression<Serialized<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>>,
    snapshotConfig: BaseExpression<Serialized<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>>,
    options: BaseExpression<Serialized<z.infer<typeof ARGO_RFS_OPTIONS>>>,
    sessionName: BaseExpression<string>,
    sourceEndpoint?: BaseExpression<string>
) {
    const base = expr.mergeDicts(
        expr.mergeDicts(
            expr.mergeDicts(
                makeTargetParamDict(targetConfig),
                makeRfsCoordinatorParamDict(rfsCoordinatorConfig)
            ),
            expr.omit(expr.deserializeRecord(options), ...ARGO_RFS_WORKFLOW_OPTION_KEYS)
        ),
        expr.mergeDicts(
            expr.makeDict({
                snapshotName: expr.get(expr.deserializeRecord(snapshotConfig), "snapshotName"),
                sourceVersion: sourceVersion,
                sessionName: sessionName,
                luceneDir: "/tmp",
                cleanLocalDirs: true
            }),
            makeRepoParamDict(
                expr.omit(expr.get(expr.deserializeRecord(snapshotConfig), "repoConfig"), "s3RoleArn"),
                true)
        )
    );

    // Pass sourceHost for Solr backup migrations (RFS detects Solr from sourceVersion)
    if (sourceEndpoint) {
        return expr.mergeDicts(base,
            expr.ternary(
                expr.isEmpty(sourceEndpoint),
                expr.makeDict({}),
                expr.makeDict({ sourceHost: sourceEndpoint })
            )
        );
    }
    return base;
}

function getRfsDeploymentName(sessionName: BaseExpression<string>) {
    return expr.concat(sessionName, expr.literal("-rfs"));
}

function getRfsDeploymentManifest
(args: {
    workflowName: BaseExpression<string>,
    jsonConfig: BaseExpression<string>
    sessionName: BaseExpression<string>,
    podReplicas: BaseExpression<number>,
    targetBasicCredsSecretNameOrEmpty: AllowLiteralOrExpression<string>,
    coordinatorBasicCredsSecretNameOrEmpty: AllowLiteralOrExpression<string>,

    useLocalstackAwsCreds: BaseExpression<boolean>,
    loggingConfigMap: BaseExpression<string>,
    jvmArgs: BaseExpression<string>,

    rfsImageName: BaseExpression<string>,
    rfsImagePullPolicy: BaseExpression<IMAGE_PULL_POLICY>,
    resources: BaseExpression<ResourceRequirementsType>,
    crdName: BaseExpression<string>,
    crdUid: BaseExpression<string>,

    sourceK8sLabel: BaseExpression<string>,
    targetK8sLabel: BaseExpression<string>,
    snapshotK8sLabel: BaseExpression<string>,
    fromSnapshotMigrationK8sLabel: BaseExpression<string>,
    taskK8sLabel: BaseExpression<string>
}): Deployment {
    const ownerReferences: OwnerReference[] = [{
        apiVersion: "migrations.opensearch.org/v1alpha1",
        kind: "SnapshotMigration",
        name: makeDirectTypeProxy(args.crdName),
        uid: makeDirectTypeProxy(args.crdUid),
        controller: false,
        blockOwnerDeletion: true
    }];
    const useCustomLogging = expr.not(expr.isEmpty(args.loggingConfigMap));
    const baseContainerDefinition = {
        name: CONTAINER_NAMES.BULK_LOADER,
        image: makeStringTypeProxy(args.rfsImageName),
        imagePullPolicy: makeStringTypeProxy(args.rfsImagePullPolicy),
        command: ["/rfs-app/runJavaWithClasspathWithRepeat.sh"],
        env: [
            ...getTargetHttpAuthCredsEnvVars(args.targetBasicCredsSecretNameOrEmpty),
            ...getCoordinatorHttpAuthCredsEnvVars(args.coordinatorBasicCredsSecretNameOrEmpty),
            // We don't have a mechanism to scrape these off disk so need to disable this to avoid filling up the disk
            {
                name: "FAILED_REQUESTS_LOGGER_LEVEL",
                value: "OFF"
            },
            {
                name: "CONSOLE_LOG_FORMAT",
                value: "json"
            }
        ],
        args: [
            "org.opensearch.migrations.RfsMigrateDocuments",
            "---INLINE-JSON",
            makeStringTypeProxy(args.jsonConfig)
        ],
        resources: makeDirectTypeProxy(args.resources)
    };

    const finalContainerDefinition = setupTestCredsForContainer(
        args.useLocalstackAwsCreds,
        setupLog4jConfigForContainer(
            useCustomLogging,
            args.loggingConfigMap,
            {container: baseContainerDefinition, volumes: []},
            args.jvmArgs
        )
    );
    const deploymentName = getRfsDeploymentName(args.sessionName);
    return {
        apiVersion: "apps/v1",
        kind: "Deployment",
        metadata: {
            name: makeStringTypeProxy(deploymentName),
            ownerReferences,
            labels: {
                "workflows.argoproj.io/workflow": makeStringTypeProxy(args.workflowName),
                "migrations.opensearch.org/source": makeStringTypeProxy(args.sourceK8sLabel),
                "migrations.opensearch.org/target": makeStringTypeProxy(args.targetK8sLabel),
                "migrations.opensearch.org/snapshot": makeStringTypeProxy(args.snapshotK8sLabel),
                "migrations.opensearch.org/from-snapshot-migration": makeStringTypeProxy(args.fromSnapshotMigrationK8sLabel),
                "migrations.opensearch.org/task": makeStringTypeProxy(args.taskK8sLabel)
            },
        },
        spec: {
            replicas: makeDirectTypeProxy(args.podReplicas),
            strategy: {
                type: "RollingUpdate",
                rollingUpdate: {
                    maxUnavailable: 1,
                    maxSurge: 1
                }
            },
            selector: {
                matchLabels: {
                    app: "bulk-loader",
                    "deployment-name": makeStringTypeProxy(deploymentName)
                },
            },
            template: {
                metadata: {
                    labels: {
                        app: "bulk-loader",
                        "deployment-name": makeStringTypeProxy(deploymentName),
                        "workflows.argoproj.io/workflow": makeStringTypeProxy(args.workflowName),
                        "migrations.opensearch.org/source": makeStringTypeProxy(args.sourceK8sLabel),
                        "migrations.opensearch.org/target": makeStringTypeProxy(args.targetK8sLabel),
                        "migrations.opensearch.org/snapshot": makeStringTypeProxy(args.snapshotK8sLabel),
                        "migrations.opensearch.org/from-snapshot-migration": makeStringTypeProxy(args.fromSnapshotMigrationK8sLabel),
                        "migrations.opensearch.org/task": makeStringTypeProxy(args.taskK8sLabel)
                    },
                },
                spec: {
                    serviceAccountName: "argo-workflow-executor",
                    containers: [finalContainerDefinition.container],
                    volumes: [...finalContainerDefinition.volumes]
                }
            }
        }
    } as Deployment;
}


function getCheckBackfillStatusScript(sessionName: BaseExpression<string>) {
    const template = `
set -e -x
touch /tmp/status-output.txt
touch /tmp/phase-output.txt

status=$(console --config-file=/config/migration_services.yaml backfill status --deep-check)

# Check if initializing
if [[ "$status" == "Shards are initializing" ]]; then
    echo "Shards are initializing" > /tmp/status-output.txt
else
    # Format detailed status
    echo "$status" | awk '
    /^Backfill status:/ { status = $3 }
    /^Backfill percentage_completed:/ { pct = $3 }
    /^Backfill eta_ms:/ { eta = $3 }
    /^Backfill shard_total:/ { total = $3 }
    /^Backfill shard_complete:/ { complete = $3 }
    /^Backfill shard_in_progress:/ { progress = $3 }
    /^Backfill shard_waiting:/ { waiting = $3 }

    END {
        gsub(/^[^.]+\\./, "", status)
        eta_str = (eta == "" || eta == "None") ? "unknown" : int(eta/1000) "s"
        printf "complete: %.2f%%, ETA: %s; shards in-progress: %d; remaining: %d; shards complete/total: %d/%d\\n", 
               pct, eta_str, progress, waiting, complete, total
    }
    ' > /tmp/status-output.txt
fi

# Check completion status - exit 0 only if complete, otherwise exit 1
echo "$status" | awk '
/^Backfill shard_total:/ {total=$3} 
/^Backfill shard_complete:/ {complete=$3} 
END {
  if(total > 0 && total==complete) {
    exit 0
  } else {
    exit 1
  }
}' || (echo Checked > /tmp/phase-output.txt && exit 1)
`;
    return expr.fillTemplate(template, {"SESSION_NAME": sessionName});
}

function makeSnapshotMigrationManifest(
    snapshotMigrationConfig: BaseExpression<Serialized<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>>>,
) {
    const config = expr.deserializeRecord(snapshotMigrationConfig) as any;
    const targetConfig = expr.get(config, "targetConfig") as any;
    const snapshotNameResolution = expr.get(config, "snapshotNameResolution") as any;
    return {
        apiVersion: "migrations.opensearch.org/v1alpha1",
        kind: "SnapshotMigration",
        metadata: {
            name: makeStringTypeProxy(expr.concat(
                expr.get(config, "sourceLabel") as any,
                expr.literal("-"),
                expr.get(targetConfig, "label") as any,
                expr.literal("-"),
                expr.get(config, "label") as any,
            )),
            labels: {
                "workflows.argoproj.io/run-uid": makeStringTypeProxy(expr.getWorkflowValue("uid"))
            }
        },
        spec: {
            dependsOn: makeDirectTypeProxy(expr.ternary(
                expr.hasKey(snapshotNameResolution, "dataSnapshotResourceName"),
                expr.toArray(expr.getLoose(snapshotNameResolution, "dataSnapshotResourceName")),
                expr.literal([])
            ) as any),
            sourceVersion: makeStringTypeProxy(expr.get(config, "sourceVersion") as any),
            sourceLabel: makeStringTypeProxy(expr.get(config, "sourceLabel") as any),
            targetLabel: makeStringTypeProxy(expr.get(targetConfig, "label") as any),
            snapshotLabel: makeStringTypeProxy(expr.dig(config, ["snapshotConfig", "label"], expr.literal(""))),
        }
    };
}

export const DocumentBulkLoad = WorkflowBuilder.create({
    k8sResourceName: "document-bulk-load",
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)

    .addTemplate("applySnapshotMigration", t => t
        .addRequiredInput("snapshotMigrationConfig", typeToken<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                manifest: makeSnapshotMigrationManifest(b.inputs.snapshotMigrationConfig)
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )

    .addTemplate("applySnapshotMigrationWithRetry", t => t
        .addRequiredInput("snapshotMigrationConfig", typeToken<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>>())
        .addRequiredInput("resourceName", typeToken<string>())
        .addRequiredInput("retryGateName", typeToken<string>())
        .addOptionalInput("retryGroupName_view", c => "Apply")

        .addSteps(b => b
            .addStep("tryApply", INTERNAL, "applySnapshotMigration", c =>
                c.register({
                    snapshotMigrationConfig: b.inputs.snapshotMigrationConfig,
                }),
                {continueOn: {failed: true}}
            )
            .addStep("waitForFix", ResourceManagement, "waitForApproval", c =>
                c.register({
                    resourceName: b.inputs.retryGateName,
                }),
                {when: c => ({templateExp: expr.equals(c.tryApply.status, "Failed")})}
            )
            .addStep("patchApproval", ResourceManagement, "patchApprovalAnnotation", c =>
                c.register({
                    resourceApiVersion: expr.literal("migrations.opensearch.org/v1alpha1"),
                    resourceKind: expr.literal("SnapshotMigration"),
                    resourceName: b.inputs.resourceName,
                }),
                {when: c => ({templateExp: expr.equals(c.waitForFix.status, "Succeeded")})}
            )
            .addStep("resetGate", ResourceManagement, "patchApprovalGatePhase", c =>
                c.register({
                    resourceName: b.inputs.retryGateName,
                    phase: expr.literal("Pending"),
                }),
                {when: c => ({templateExp: expr.equals(c.patchApproval.status, "Succeeded")})}
            )
            .addStepToSelf("retryLoop", c =>
                c.register({
                    snapshotMigrationConfig: b.inputs.snapshotMigrationConfig,
                    resourceName: b.inputs.resourceName,
                    retryGateName: b.inputs.retryGateName,
                    retryGroupName_view: b.inputs.retryGroupName_view,
                }),
                {when: c => ({templateExp: expr.equals(c.resetGate.status, "Succeeded")})}
            )
        )
    )


    .addTemplate("stopHistoricalBackfill", t => t
        .addRequiredInput("sessionName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "delete", flags: ["--ignore-not-found"],
                manifest: {
                    "apiVersion": "apps/v1",
                    "kind": "Deployment",
                    "metadata": {
                        "name": getRfsDeploymentName(b.inputs.sessionName)
                    }
                }
            })
        )
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )


    .addTemplate("waitForCompletionInternal", t => t
        .addRequiredInput("configContents", typeToken<z.infer<typeof CONSOLE_SERVICES_CONFIG_FILE>>())
        .addRequiredInput("sessionName", typeToken<string>())
        .addRequiredInput("sourceK8sLabel", typeToken<string>())
        .addRequiredInput("targetK8sLabel", typeToken<string>())
        .addRequiredInput("snapshotK8sLabel", typeToken<string>())
        .addRequiredInput("fromSnapshotMigrationK8sLabel", typeToken<string>())
        .addOptionalInput("taskK8sLabel", c => "reindexFromSnapshotStatusCheck")
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addSteps(b => b
            .addStep("checkBackfillStatus", MigrationConsole, "runMigrationCommandForStatus", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    command: getCheckBackfillStatusScript(b.inputs.sessionName)
                }))
        )
        .addRetryParameters({
            limit: "200",
            retryPolicy: "Always",
            backoff: {duration: "5", factor: "2", cap: "300"}
        })
    )

    .addTemplate("waitForCompletion", t => t
        .addRequiredInput("configContents", typeToken<z.infer<typeof CONSOLE_SERVICES_CONFIG_FILE>>())
        .addRequiredInput("sessionName", typeToken<string>())
        .addRequiredInput("sourceK8sLabel", typeToken<string>())
        .addRequiredInput("targetK8sLabel", typeToken<string>())
        .addRequiredInput("snapshotK8sLabel", typeToken<string>())
        .addRequiredInput("fromSnapshotMigrationK8sLabel", typeToken<string>())
        .addOptionalInput("groupName_view", c => "checks")
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addSteps(b => b
            .addStep("runStatusChecks", INTERNAL, "waitForCompletionInternal", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    configContents: b.inputs.configContents,
                    sessionName: b.inputs.sessionName,
                    sourceK8sLabel: b.inputs.sourceK8sLabel,
                    targetK8sLabel: b.inputs.targetK8sLabel,
                    snapshotK8sLabel: b.inputs.snapshotK8sLabel,
                    fromSnapshotMigrationK8sLabel: b.inputs.fromSnapshotMigrationK8sLabel
                }))
        )
    )


    .addTemplate("startHistoricalBackfill", t => t
        .addRequiredInput("sessionName", typeToken<string>())
        .addRequiredInput("rfsJsonConfig", typeToken<string>())
        .addRequiredInput("targetBasicCredsSecretNameOrEmpty", typeToken<string>())
        .addRequiredInput("coordinatorBasicCredsSecretNameOrEmpty", typeToken<string>())
        .addRequiredInput("podReplicas", typeToken<number>())
        .addRequiredInput("jvmArgs", typeToken<string>())
        .addRequiredInput("loggingConfigurationOverrideConfigMap", typeToken<string>())
        .addRequiredInput("useLocalStack", typeToken<boolean>(), "Only used for local testing")
        .addRequiredInput("resources", typeToken<ResourceRequirementsType>())
        .addRequiredInput("crdName", typeToken<string>())
        .addRequiredInput("crdUid", typeToken<string>())
        .addRequiredInput("sourceK8sLabel", typeToken<string>())
        .addRequiredInput("targetK8sLabel", typeToken<string>())
        .addRequiredInput("snapshotK8sLabel", typeToken<string>())
        .addRequiredInput("fromSnapshotMigrationK8sLabel", typeToken<string>())
        .addOptionalInput("taskK8sLabel", c => "reindexFromSnapshot")
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["ReindexFromSnapshot"]))

        .addResourceTask(b => b
            .setDefinition({
                action: "create",
                setOwnerReference: false,
                manifest: getRfsDeploymentManifest({
                    podReplicas: expr.deserializeRecord(b.inputs.podReplicas),
                    loggingConfigMap: b.inputs.loggingConfigurationOverrideConfigMap,
                    jvmArgs: b.inputs.jvmArgs,
                    useLocalstackAwsCreds: expr.deserializeRecord(b.inputs.useLocalStack),
                    sessionName: b.inputs.sessionName,
                    targetBasicCredsSecretNameOrEmpty: b.inputs.targetBasicCredsSecretNameOrEmpty,
                    coordinatorBasicCredsSecretNameOrEmpty: b.inputs.coordinatorBasicCredsSecretNameOrEmpty,
                    rfsImageName: b.inputs.imageReindexFromSnapshotLocation,
                    rfsImagePullPolicy: b.inputs.imageReindexFromSnapshotPullPolicy,
                    workflowName: expr.getWorkflowValue("name"),
                    jsonConfig: expr.toBase64(b.inputs.rfsJsonConfig),
                    resources: expr.deserializeRecord(b.inputs.resources),
                    crdName: b.inputs.crdName,
                    crdUid: b.inputs.crdUid,
                    sourceK8sLabel: b.inputs.sourceK8sLabel,
                    targetK8sLabel: b.inputs.targetK8sLabel,
                    snapshotK8sLabel: b.inputs.snapshotK8sLabel,
                    fromSnapshotMigrationK8sLabel: b.inputs.fromSnapshotMigrationK8sLabel,
                    taskK8sLabel: b.inputs.taskK8sLabel,
                })
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )


    .addTemplate("startHistoricalBackfillFromConfig", t => t
        .addRequiredInput("sessionName", typeToken<string>())
        .addRequiredInput("sourceVersion", typeToken<z.infer<typeof CLUSTER_VERSION_STRING>>())
        .addRequiredInput("sourceLabel", typeToken<string>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("rfsCoordinatorConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("documentBackfillConfig", typeToken<z.infer<typeof ARGO_RFS_OPTIONS>>())
        .addRequiredInput("migrationLabel", typeToken<string>())
        .addRequiredInput("crdName", typeToken<string>())
        .addRequiredInput("crdUid", typeToken<string>())
        .addOptionalInput("sourceEndpoint", c => expr.literal(""))
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["ReindexFromSnapshot"]))

        .addSteps(b => b
            .addStep("startHistoricalBackfill", INTERNAL, "startHistoricalBackfill", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    podReplicas: expr.dig(expr.deserializeRecord(b.inputs.documentBackfillConfig), ["podReplicas"], 1),
                    targetBasicCredsSecretNameOrEmpty: getHttpAuthSecretName(b.inputs.targetConfig),
                    coordinatorBasicCredsSecretNameOrEmpty: getHttpAuthSecretName(b.inputs.rfsCoordinatorConfig),
                    loggingConfigurationOverrideConfigMap: expr.dig(expr.deserializeRecord(b.inputs.documentBackfillConfig), ["loggingConfigurationOverrideConfigMap"], ""),
                    jvmArgs: expr.dig(expr.deserializeRecord(b.inputs.documentBackfillConfig), ["jvmArgs"], ""),
                    useLocalStack: expr.dig(expr.deserializeRecord(b.inputs.snapshotConfig), ["repoConfig", "useLocalStack"], false),
                    rfsJsonConfig: expr.asString(expr.serialize(
                        makeParamsDict(b.inputs.sourceVersion,
                            b.inputs.targetConfig,
                            b.inputs.rfsCoordinatorConfig,
                            b.inputs.snapshotConfig,
                            b.inputs.documentBackfillConfig,
                            b.inputs.sessionName,
                            b.inputs.sourceEndpoint)
                    )),
                    resources: expr.serialize(expr.jsonPathStrict(b.inputs.documentBackfillConfig, "resources")),
                    crdName: b.inputs.crdName,
                    crdUid: b.inputs.crdUid,
                    sourceK8sLabel: b.inputs.sourceLabel,
                    targetK8sLabel: expr.jsonPathStrict(b.inputs.targetConfig, "label"),
                    snapshotK8sLabel: expr.jsonPathStrict(b.inputs.snapshotConfig, "label"),
                    fromSnapshotMigrationK8sLabel: b.inputs.migrationLabel
                })
            )
        )
    )


    .addTemplate("runBulkLoad", t => t
        .addRequiredInput("sourceVersion", typeToken<z.infer<typeof CLUSTER_VERSION_STRING>>())
        .addRequiredInput("sourceLabel", typeToken<string>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("rfsCoordinatorConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("sessionName", typeToken<string>())
        .addRequiredInput("documentBackfillConfig", typeToken<z.infer<typeof ARGO_RFS_OPTIONS>>())
        .addRequiredInput("migrationLabel", typeToken<string>())
        .addRequiredInput("crdName", typeToken<string>())
        .addRequiredInput("crdUid", typeToken<string>())
        .addOptionalInput("sourceEndpoint", c => expr.literal(""))
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["ReindexFromSnapshot", "MigrationConsole"]))

        .addSteps(b => b
            .addStep("startHistoricalBackfillFromConfig", INTERNAL, "startHistoricalBackfillFromConfig", c =>
                c.register({
                    ...selectInputsForRegister(b, c)
                }))
            .addStep("setupWaitForCompletion", MigrationConsole, "getConsoleConfig", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    targetConfig: b.inputs.rfsCoordinatorConfig,
                    backfillSession: expr.serialize(expr.makeDict({
                        sessionName: b.inputs.sessionName,
                        deploymentName: getRfsDeploymentName(b.inputs.sessionName)
                    }))
                }))
            .addStep("waitForCompletion", INTERNAL, "waitForCompletion", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    configContents: c.steps.setupWaitForCompletion.outputs.configContents,
                    sourceK8sLabel: b.inputs.sourceLabel,
                    targetK8sLabel: expr.jsonPathStrict(b.inputs.targetConfig, "label"),
                    snapshotK8sLabel: expr.jsonPathStrict(b.inputs.snapshotConfig, "label"),
                    fromSnapshotMigrationK8sLabel: b.inputs.migrationLabel
                }))
            .addStep("stopHistoricalBackfill", INTERNAL, "stopHistoricalBackfill", c =>
                c.register({sessionName: b.inputs.sessionName}))
        )
    )


    .addTemplate("doNothing", t => t
        .addSteps(b => b.addStepGroup(c => c)))


    .addTemplate("setupAndRunBulkLoad", t => t
        .addRequiredInput("sourceVersion", typeToken<z.infer<typeof CLUSTER_VERSION_STRING>>())
        .addRequiredInput("sourceLabel", typeToken<string>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("sessionName", typeToken<string>())
        .addRequiredInput("documentBackfillConfig", typeToken<z.infer<typeof ARGO_RFS_OPTIONS>>())
        .addRequiredInput("migrationLabel", typeToken<string>())
        .addRequiredInput("crdName", typeToken<string>())
        .addRequiredInput("crdUid", typeToken<string>())
        .addOptionalInput("sourceEndpoint", c => expr.literal(""))
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["ReindexFromSnapshot", "MigrationConsole", "CoordinatorCluster"]))

        .addSteps(b => {
            const createRfsCluster = shouldCreateRfsWorkCoordinationCluster(b.inputs.documentBackfillConfig);
            return b
                // (conditional) Deploy an OpenSearch cluster for RFS work coordination
                .addStep("createRfsCoordinator", RfsCoordinatorCluster, "createRfsCoordinator", c =>
                        c.register({
                            clusterName: getRfsCoordinatorClusterName(b.inputs.sessionName),
                            coordinatorImage: b.inputs.imageCoordinatorClusterLocation,
                            ownerName: b.inputs.crdName,
                            ownerUid: b.inputs.crdUid
                        }),
                    {when: {templateExp: createRfsCluster}}
                )

                // Always run bulk load, use deployed cluster or target cluster based on flag 'createRfsCluster'
                .addStep("runBulkLoad", INTERNAL, "runBulkLoad", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        rfsCoordinatorConfig: expr.ternary(
                            createRfsCluster,
                            expr.serialize(makeRfsCoordinatorConfig(getRfsCoordinatorClusterName(b.inputs.sessionName))),
                            b.inputs.targetConfig
                        )
                    }))

                // (conditional) Cleanup OpenSearch cluster used for RFS work coordination
                .addStep("cleanupRfsCoordinator", RfsCoordinatorCluster, "deleteRfsCoordinator", c =>
                        c.register({
                            clusterName: getRfsCoordinatorClusterName(b.inputs.sessionName)
                        }),
                    {when: {templateExp: createRfsCluster}}
                );
        })
    )

    .getFullScope();
