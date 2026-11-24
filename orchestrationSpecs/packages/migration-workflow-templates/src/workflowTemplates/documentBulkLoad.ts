import {z} from "zod";
import {
    CLUSTER_VERSION_STRING,
    COMPLETE_SNAPSHOT_CONFIG,
    CONSOLE_SERVICES_CONFIG_FILE,
    DEFAULT_RESOURCES,
    NAMED_TARGET_CLUSTER_CONFIG,
    ResourceRequirementsType,
    ARGO_FILE_SOURCE_VOLUME,
    ARGO_FILE_SOURCE_VOLUME_MOUNT,
    ARGO_RFS_OPTIONS,
    ARGO_RFS_WORKFLOW_OPTION_KEYS,
} from "@opensearch-migrations/schemas";
import {MigrationConsole} from "./migrationConsole";
import {ResourceManagement} from "./resourceManagement";
import {CONTAINER_NAMES} from "../containerNames";

import {
    AllowLiteralOrExpression,
    BaseExpression,
    defineParam,
    defineRequiredParam,
    expr,
    IMAGE_PULL_POLICY,
    InputParamsToExpressions,
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
    setupFileSourcesForContainer,
    setupLog4jConfigForContainer,
    setupTestCredsForContainer,
} from "./commonUtils/containerFragments";
import {CommonWorkflowParameters, WORKFLOW_SCRIPTS_ROOT_ENV, cloudProviderResolvedInput, workflowIdentityEnvVars, workflowScriptCommand, workflowScriptRootEnvVars} from "./commonUtils/workflowParameters";
import {makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";
import {makeTargetParamDict, makeRfsCoordinatorParamDict} from "./commonUtils/clusterSettingManipulators";
import {getHttpAuthSecretName} from "./commonUtils/clusterSettingManipulators";
import {
    getAzureStorageCredsEnvVars,
    getCoordinatorHttpAuthCredsEnvVars,
    getTargetHttpAuthCredsEnvVars
} from "./commonUtils/basicCredsGetters";
import {K8S_RESOURCE_RETRY_STRATEGY} from "./commonUtils/resourceRetryStrategy";
import {RfsCoordinatorCluster, getRfsCoordinatorClusterName, makeRfsCoordinatorConfig} from "./rfsCoordinatorCluster";
import {makePodDisruptionBudgetDefinition} from "./commonUtils/podDisruptionBudget";
import {
    minAvailableForSingleReplicaDependency,
    SCALABLE_WORKLOAD_INPUTS,
    scalingFromOptions,
    workflowParameterAsNumber,
} from "./commonUtils/scalableWorkload";
import {
    COMPONENT_LABEL,
    COMPONENTS,
    MIGRATION_RESOURCE_UID_LABEL,
    RFS_MEMORY_TIER_LABEL,
    WORKLOAD_SELECTOR_LABEL_KEYS,
    workloadSelectorLabels,
} from "./commonUtils/resourceLabels";
import {
    workloadNodePoolResolvedInput,
    workloadNodeSelector,
    workloadNodeTolerations,
    workloadZoneSpreadConstraints,
    workloadZoneSpreadResolvedInput,
} from "./commonUtils/workloadNodePool";

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
    snapshotMigrationUid: BaseExpression<string>,
    sourceEndpoint?: BaseExpression<string>
) {
    const repoConfig = expr.get(expr.deserializeRecord(snapshotConfig), "repoConfig");
    const failedDocumentStreamParams = expr.makeDict({
        // The SnapshotMigration's own UID is the failed-document-stream session id: it uniquely
        // identifies this backfill (so multiple backfills in one workflow never share a session=
        // prefix) and is exactly what the console reads back from the SnapshotMigration it reports on.
        failedDocumentStreamSessionId: snapshotMigrationUid
    });
    const base = expr.mergeDicts(
        expr.mergeDicts(
            expr.mergeDicts(
                makeTargetParamDict(targetConfig),
                makeRfsCoordinatorParamDict(rfsCoordinatorConfig)
            ),
            expr.omit(expr.deserializeRecord(options), ...ARGO_RFS_WORKFLOW_OPTION_KEYS)
        ),
        expr.mergeDicts(
            expr.mergeDicts(
                expr.makeDict({
                    snapshotName: expr.get(expr.deserializeRecord(snapshotConfig), "snapshotName"),
                    sourceVersion: sourceVersion,
                    sessionName: sessionName,
                    luceneDir: "/tmp",
                    cleanLocalDirs: true
                }),
                failedDocumentStreamParams
            ),
            makeRepoParamDict(
                expr.omit(repoConfig, "s3RoleArn"),
                true)
        )
    );
    return base;
}

export function getRfsDeploymentName(sessionName: BaseExpression<string>) {
    return expr.concat(sessionName, expr.literal("-rfs"));
}

// The failed-document-stream session id is the SnapshotMigration's own UID (see makeParamsDict).
// There is intentionally no namespace-global "current session" ConfigMap: a single ConfigMap can
// only point at one backfill, which breaks with multiple/parallel SnapshotMigrations in a workflow.
// The console instead reads the stream's config + UID directly from the SnapshotMigration it reports on.

function getRfsDoneCronJobName(sessionName: BaseExpression<string>) {
    return expr.concat(sessionName, expr.literal("-rfs-done"));
}

const RFS_MONITOR_WORKFLOW_UID_LABEL = "migrations.opensearch.org/rfs-monitor-workflow-uid";
const RFS_MONITOR_SESSION_LABEL = "migrations.opensearch.org/rfs-monitor-session";

const startHistoricalBackfillInputs = {
    sessionName: defineRequiredParam<string>(),
    rfsJsonConfig: defineRequiredParam<string>(),
    targetBasicCredsSecretNameOrEmpty: defineRequiredParam<string>(),
    coordinatorBasicCredsSecretNameOrEmpty: defineRequiredParam<string>(),
    azureCredsSecretNameOrEmpty: defineRequiredParam<string>(),
    ...SCALABLE_WORKLOAD_INPUTS,
    jvmArgs: defineRequiredParam<string>(),
    loggingConfigurationOverrideConfigMap: defineRequiredParam<string>(),
    rootLogLevel: defineRequiredParam<string>(),
    bulkRequestTraceLogLevel: defineRequiredParam<string>(),
    failedRequestsLogLevel: defineRequiredParam<string>(),
    fileSourceVolumes: defineRequiredParam<z.infer<typeof ARGO_FILE_SOURCE_VOLUME>[]>(),
    fileSourceVolumeMounts: defineRequiredParam<z.infer<typeof ARGO_FILE_SOURCE_VOLUME_MOUNT>[]>(),
    useLocalStack: defineRequiredParam<boolean>({description: "Only used for local testing"}),
    resources: defineRequiredParam<ResourceRequirementsType>(),
    crdName: defineRequiredParam<string>(),
    crdUid: defineRequiredParam<string>(),
    sourceK8sLabel: defineRequiredParam<string>(),
    targetK8sLabel: defineRequiredParam<string>(),
    snapshotK8sLabel: defineRequiredParam<string>(),
    fromSnapshotMigrationK8sLabel: defineRequiredParam<string>(),
    taskK8sLabel: defineParam<string>({expression: expr.literal("reindexFromSnapshot")}),
    ...workloadNodePoolResolvedInput("rfsNodePoolName"),
    ...workloadZoneSpreadResolvedInput(),
    ...cloudProviderResolvedInput(),
    ...makeRequiredImageParametersForKeys(["ReindexFromSnapshot"])
};


function getRfsDeploymentManifest
(args: {
    workflowName: BaseExpression<string>,
    jsonConfig: BaseExpression<string>
    sessionName: BaseExpression<string>,
    podReplicas: BaseExpression<number>,
    targetBasicCredsSecretNameOrEmpty: AllowLiteralOrExpression<string>,
    coordinatorBasicCredsSecretNameOrEmpty: AllowLiteralOrExpression<string>,
    azureCredsSecretNameOrEmpty: AllowLiteralOrExpression<string>,

    useLocalstackAwsCreds: BaseExpression<boolean>,
    loggingConfigMap: BaseExpression<string>,
    jvmArgs: BaseExpression<string>,
    rootLogLevel: BaseExpression<string>,
    bulkRequestTraceLogLevel: BaseExpression<string>,
    failedRequestsLogLevel: BaseExpression<string>,
    fileSourceVolumes: BaseExpression<any[]>,
    fileSourceVolumeMounts: BaseExpression<any[]>,

    rfsImageName: BaseExpression<string>,
    rfsImagePullPolicy: BaseExpression<IMAGE_PULL_POLICY>,
    resources: BaseExpression<ResourceRequirementsType>,
    crdName: BaseExpression<string>,
    crdUid: BaseExpression<string>,

    sourceK8sLabel: BaseExpression<string>,
    targetK8sLabel: BaseExpression<string>,
    snapshotK8sLabel: BaseExpression<string>,
    fromSnapshotMigrationK8sLabel: BaseExpression<string>,
    taskK8sLabel: BaseExpression<string>,
    workloadNodePoolName: BaseExpression<string>,
    workloadZoneSpread: BaseExpression<string>,
    workloadZoneSpreadExtras: BaseExpression<string>,
    cloudProvider: BaseExpression<string>
}): Deployment {
    const ownerReferences: OwnerReference[] = [{
        apiVersion: "migrations.opensearch.org/v1alpha1",
        kind: "SnapshotMigration",
        name: makeStringTypeProxy(args.crdName),
        uid: makeStringTypeProxy(args.crdUid),
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
            ...getAzureStorageCredsEnvVars(args.azureCredsSecretNameOrEmpty),
            // Log levels from documentBackfillConfig.logLevels, read by RFS's log4j2.properties. Terminal
            // document failures go to the durable S3 failed document stream; the pod-local FailedRequests log
            // defaults to WARN as a local-dev safety net.
            {
                name: "MIGRATIONS_ROOT_LOG_LEVEL",
                value: makeStringTypeProxy(args.rootLogLevel)
            },
            {
                name: "BULK_REQUEST_TRACE_LOG_LEVEL",
                value: makeStringTypeProxy(args.bulkRequestTraceLogLevel)
            },
            {
                name: "FAILED_REQUESTS_LOGGER_LEVEL",
                value: makeStringTypeProxy(args.failedRequestsLogLevel)
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

    const finalContainerDefinition = setupFileSourcesForContainer(
        args.fileSourceVolumes,
        args.fileSourceVolumeMounts,
        setupTestCredsForContainer(
            args.useLocalstackAwsCreds,
            setupLog4jConfigForContainer(
                useCustomLogging,
                args.loggingConfigMap,
                {container: baseContainerDefinition, volumes: []},
                args.jvmArgs
            )
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
                [COMPONENT_LABEL]: COMPONENTS.RFS_WORKER,
                "workflows.argoproj.io/workflow": makeStringTypeProxy(args.workflowName),
                "migrations.opensearch.org/source": makeStringTypeProxy(args.sourceK8sLabel),
                "migrations.opensearch.org/target": makeStringTypeProxy(args.targetK8sLabel),
                "migrations.opensearch.org/snapshot": makeStringTypeProxy(args.snapshotK8sLabel),
                "migrations.opensearch.org/from-snapshot-migration": makeStringTypeProxy(args.fromSnapshotMigrationK8sLabel),
                "migrations.opensearch.org/task": makeStringTypeProxy(args.taskK8sLabel),
                [MIGRATION_RESOURCE_UID_LABEL]: makeStringTypeProxy(args.crdUid),
            },
        },
        spec: {
            replicas: makeDirectTypeProxy(args.podReplicas),
            strategy: {
                type: "RollingUpdate",
                rollingUpdate: {
                    maxUnavailable: "5%",
                    maxSurge: "10%"
                }
            },
            selector: {
                matchLabels: workloadSelectorLabels(COMPONENTS.RFS_WORKER, args.crdUid),
            },
            template: {
                metadata: {
                    labels: {
                        [COMPONENT_LABEL]: COMPONENTS.RFS_WORKER,
                        "workflows.argoproj.io/workflow": makeStringTypeProxy(args.workflowName),
                        "migrations.opensearch.org/source": makeStringTypeProxy(args.sourceK8sLabel),
                        "migrations.opensearch.org/target": makeStringTypeProxy(args.targetK8sLabel),
                        "migrations.opensearch.org/snapshot": makeStringTypeProxy(args.snapshotK8sLabel),
                        "migrations.opensearch.org/from-snapshot-migration": makeStringTypeProxy(args.fromSnapshotMigrationK8sLabel),
                        "migrations.opensearch.org/task": makeStringTypeProxy(args.taskK8sLabel),
                        [MIGRATION_RESOURCE_UID_LABEL]: makeStringTypeProxy(args.crdUid),
                        // Microsoft Entra Workload ID mutates only pods labeled "true"; "false" leaves them alone.
                        "azure.workload.identity/use": makeStringTypeProxy(expr.ternary(
                            expr.equals(args.cloudProvider, expr.literal("azure")),
                            expr.literal("true"),
                            expr.literal("false"))),
                    },
                },
                spec: {
                    serviceAccountName: "argo-workflow-executor",
                    nodeSelector: makeDirectTypeProxy(workloadNodeSelector(args.workloadNodePoolName)),
                    tolerations: makeDirectTypeProxy(workloadNodeTolerations(args.workloadNodePoolName)),
                    topologySpreadConstraints: makeDirectTypeProxy(
                        workloadZoneSpreadConstraints(
                            args.workloadZoneSpread,
                            args.workloadZoneSpreadExtras,
                            WORKLOAD_SELECTOR_LABEL_KEYS,
                        )
                    ),
                    containers: [finalContainerDefinition.container],
                    volumes: finalContainerDefinition.volumes
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

status_json=$(console --config-file=/config/migration_services.yaml --json backfill status --deep-check)
status=$(echo "$status_json" | jq -r '.status')

failed_document_stream_loc=$(echo "$status_json" | jq -r '.failed_document_stream_location // empty')
failed_documents_present=$(echo "$status_json" | jq -r '.failed_documents_present | tostring')
failed_document_stream_suffix=""
if [[ -n "$failed_document_stream_loc" ]]; then
    case "$failed_documents_present" in
        true)  failed_document_stream_suffix="; failed documents recorded at $failed_document_stream_loc" ;;
        false) ;;
        *)     failed_document_stream_suffix="; failed document stream location: $failed_document_stream_loc (unavailable)" ;;
    esac
fi

# Check if initializing
if [[ "$status" == "Pending" ]]; then
    echo "Shards are initializing$failed_document_stream_suffix" > /tmp/status-output.txt
else
    eval "$(echo "$status_json" | jq -r '
      @sh "pct=\(.percentage_completed // 0)
      eta=\(if .eta_ms == null then "unknown" else "\(.eta_ms / 1000 | floor)s" end)
      progress=\(.shard_in_progress // 0)
      waiting=\(.shard_waiting // 0)
      complete=\(.shard_complete // 0)
      total=\(.shard_total // 0)"
    ')"
    printf "complete: %.2f%%, ETA: %s; shards in-progress: %d; remaining: %d; shards complete/total: %d/%d%s\\n" \
           "$pct" "$eta" "$progress" "$waiting" "$complete" "$total" "$failed_document_stream_suffix" > /tmp/status-output.txt
fi

# Exit 0 on terminal completion; CompletedWithErrors is still done.
if [[ "$status" == "Completed" || "$status" == "CompletedWithErrors" ]]; then
  exit 0
else
  echo Checked > /tmp/phase-output.txt
  exit 1
fi
`;
    return expr.fillTemplate(template, {"SESSION_NAME": sessionName});
}

const documentBulkLoadBaseBuilder = WorkflowBuilder.create({
    k8sResourceName: "document-bulk-load",
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)

    .addTemplate("applyRfsDoneCronJob", t => t
        .addRequiredInput("sessionName", typeToken<string>())
        .addRequiredInput("crdName", typeToken<string>())
        .addRequiredInput("crdUid", typeToken<string>())
        .addRequiredInput("configChecksum", typeToken<string>())
        .addRequiredInput("checksumForReplayer", typeToken<string>())
        .addRequiredInput("usesDedicatedCoordinator", typeToken<string>())
        .addRequiredInput("fromSnapshotMigrationK8sLabel", typeToken<string>())
        .addRequiredInput("consoleConfigContents", typeToken<z.infer<typeof CONSOLE_SERVICES_CONFIG_FILE>>())
        // Memory-tier scaling: both empty when the feature is off, and the apply script then
        // renders exactly the same CronJob it always has.
        .addOptionalInput("memoryScalingEnabled", c => "")
        .addOptionalInput("tierPolicyConfigMap", c => "")
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addContainer(c => c
            .addImageInfo(c.inputs.imageMigrationConsoleLocation, c.inputs.imageMigrationConsolePullPolicy)
            .addResources(DEFAULT_RESOURCES.SHELL_MIGRATION_CONSOLE_CLI)
            .addCommand(["/bin/bash", "-lc"])
            .addEnvVar("CRONJOB_NAME", getRfsDoneCronJobName(c.inputs.sessionName))
            .addEnvVarsFromRecord({
                SESSION_NAME: c.inputs.sessionName,
                ...workflowIdentityEnvVars(),
                SNAPSHOT_MIGRATION_NAME: c.inputs.crdName,
                SM_UID: c.inputs.crdUid,
                CONFIG_CHECKSUM: c.inputs.configChecksum,
                CHECKSUM_FOR_REPLAYER: c.inputs.checksumForReplayer,
                RFS_DEPLOYMENT_NAME: getRfsDeploymentName(c.inputs.sessionName),
                RFS_COORDINATOR_NAME: getRfsCoordinatorClusterName(c.inputs.sessionName),
                USES_DEDICATED_COORDINATOR: c.inputs.usesDedicatedCoordinator,
                CONSOLE_IMAGE: c.inputs.imageMigrationConsoleLocation,
                CONSOLE_IMAGE_PULL_POLICY: c.inputs.imageMigrationConsolePullPolicy,
                FROM_SNAPSHOT_MIGRATION_LABEL: c.inputs.fromSnapshotMigrationK8sLabel,
                CONSOLE_CONFIG_BASE64: expr.toBase64YamlSafe(expr.asString(c.inputs.consoleConfigContents)),
                RFS_MONITOR_WORKFLOW_UID_LABEL: expr.literal(RFS_MONITOR_WORKFLOW_UID_LABEL),
                RFS_MONITOR_SESSION_LABEL: expr.literal(RFS_MONITOR_SESSION_LABEL),
                MIGRATION_RESOURCE_UID_LABEL: expr.literal(MIGRATION_RESOURCE_UID_LABEL),
                COMPONENT_LABEL: expr.literal(COMPONENT_LABEL),
                MONITOR_COMPONENT: expr.literal(COMPONENTS.RFS_MONITOR),
                MONITOR_TASK: expr.literal("reindexFromSnapshot"),
                MEMORY_SCALING_ENABLED: c.inputs.memoryScalingEnabled,
                TIER_POLICY_CONFIGMAP: c.inputs.tierPolicyConfigMap,
                RFS_MEMORY_TIER_LABEL: expr.literal(RFS_MEMORY_TIER_LABEL),
                ...workflowScriptRootEnvVars(t.inputs.workflowParameters.workflowScriptsRoot)
            })
            .addArgs([workflowScriptCommand("applyRfsMonitorCronJob.sh")])
        )
        .addRetryParameters({
            limit: "5",
            retryPolicy: "Always",
            backoff: {duration: "2", factor: "2", cap: "30"}
        })
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

type StartHistoricalBackfillInputExpressions = InputParamsToExpressions<typeof startHistoricalBackfillInputs>;

function makeRfsDeploymentDefinition(
    inputs: StartHistoricalBackfillInputExpressions
) {
    return {
        action: "apply" as const,
        setOwnerReference: false,
        manifest: getRfsDeploymentManifest({
            podReplicas: expr.deserializeRecord(inputs.podReplicas),
            loggingConfigMap: inputs.loggingConfigurationOverrideConfigMap,
            jvmArgs: inputs.jvmArgs,
            rootLogLevel: inputs.rootLogLevel,
            bulkRequestTraceLogLevel: inputs.bulkRequestTraceLogLevel,
            failedRequestsLogLevel: inputs.failedRequestsLogLevel,
            fileSourceVolumes: expr.deserializeRecord(inputs.fileSourceVolumes),
            fileSourceVolumeMounts: expr.deserializeRecord(inputs.fileSourceVolumeMounts),
            useLocalstackAwsCreds: expr.deserializeRecord(inputs.useLocalStack),
            sessionName: inputs.sessionName,
            targetBasicCredsSecretNameOrEmpty: inputs.targetBasicCredsSecretNameOrEmpty,
            coordinatorBasicCredsSecretNameOrEmpty: inputs.coordinatorBasicCredsSecretNameOrEmpty,
            azureCredsSecretNameOrEmpty: inputs.azureCredsSecretNameOrEmpty,
            rfsImageName: inputs.imageReindexFromSnapshotLocation,
            rfsImagePullPolicy: inputs.imageReindexFromSnapshotPullPolicy,
            workflowName: expr.getWorkflowValue("name"),
            jsonConfig: expr.toBase64YamlSafe(inputs.rfsJsonConfig),
            resources: expr.deserializeRecord(inputs.resources),
            crdName: inputs.crdName,
            crdUid: inputs.crdUid,
            sourceK8sLabel: inputs.sourceK8sLabel,
            targetK8sLabel: inputs.targetK8sLabel,
            snapshotK8sLabel: inputs.snapshotK8sLabel,
            fromSnapshotMigrationK8sLabel: inputs.fromSnapshotMigrationK8sLabel,
            taskK8sLabel: inputs.taskK8sLabel,
            workloadNodePoolName: inputs.workloadNodePoolName,
            workloadZoneSpread: inputs.workloadZoneSpread,
            workloadZoneSpreadExtras: inputs.workloadZoneSpreadExtras,
            cloudProvider: inputs.cloudProvider,
        })
    };
}

function makeRfsPodDisruptionBudgetDefinition(
    inputs: StartHistoricalBackfillInputExpressions
) {
    const deploymentName = getRfsDeploymentName(inputs.sessionName);
    const labels = {
        [COMPONENT_LABEL]: COMPONENTS.RFS_WORKER,
        "workflows.argoproj.io/workflow": expr.getWorkflowValue("name"),
        "migrations.opensearch.org/source": inputs.sourceK8sLabel,
        "migrations.opensearch.org/target": inputs.targetK8sLabel,
        "migrations.opensearch.org/snapshot": inputs.snapshotK8sLabel,
        "migrations.opensearch.org/from-snapshot-migration": inputs.fromSnapshotMigrationK8sLabel,
        "migrations.opensearch.org/task": inputs.taskK8sLabel,
    };
    const ownerReferences: OwnerReference[] = [{
        apiVersion: "migrations.opensearch.org/v1alpha1",
        kind: "SnapshotMigration",
        name: makeStringTypeProxy(inputs.crdName),
        uid: makeStringTypeProxy(inputs.crdUid),
        controller: false,
        blockOwnerDeletion: true
    }];
    return makePodDisruptionBudgetDefinition({
        name: deploymentName,
        minAvailable: workflowParameterAsNumber(inputs.minPodReplicas),
        matchLabels: workloadSelectorLabels(COMPONENTS.RFS_WORKER, inputs.crdUid),
        labels,
        ownerReferences,
    });
}

// ── Memory-tier scaling additions ────────────────────────────────────────────
// Everything below is exercised only when documentBackfillConfig.memoryScaling is configured;
// the feature-off path uses the unchanged createRfsDeployment/createRfsPodDisruptionBudget
// templates above, byte for byte.

/** Where the crash-witness sidecar and the bulk-loader share crash verdicts (pod-lifetime evidence). */
export const CRASH_VERDICTS_VOLUME_NAME = "crash-verdicts";
export const CRASH_VERDICTS_MOUNT_PATH = "/var/rfs-crash-verdicts";
export const CRASH_VERDICT_FILE_PATH = CRASH_VERDICTS_MOUNT_PATH + "/verdicts.jsonl";
// Comfortably above the worker's shutdown path's worst case (the failed-document-stream flush is
// bounded at 5 minutes) so a busy pod that loses the pod-deletion-cost race can still checkpoint.
export const RFS_TERMINATION_GRACE_PERIOD_SECONDS = 390;

export function getRfsTierDeploymentName(sessionName: BaseExpression<string>, memoryTier: BaseExpression<string>) {
    return expr.concat(getRfsDeploymentName(sessionName), expr.literal("-t"), memoryTier);
}

function getCrashWitnessContainer(args: {
    consoleImageName: BaseExpression<string>,
    consoleImagePullPolicy: BaseExpression<IMAGE_PULL_POLICY>,
    workflowScriptsRoot: BaseExpression<string>,
}) {
    return {
        name: CONTAINER_NAMES.CRASH_WITNESS,
        image: makeStringTypeProxy(args.consoleImageName),
        imagePullPolicy: makeStringTypeProxy(args.consoleImagePullPolicy),
        command: ["/bin/bash", "-lc"],
        args: [`exec python3 "\${${WORKFLOW_SCRIPTS_ROOT_ENV}}/rfsCrashWitness.py"`],
        env: [
            {name: "POD_NAME", valueFrom: {fieldRef: {fieldPath: "metadata.name"}}},
            {name: "POD_NAMESPACE", valueFrom: {fieldRef: {fieldPath: "metadata.namespace"}}},
            {name: "WATCHED_CONTAINER_NAME", value: CONTAINER_NAMES.BULK_LOADER},
            {name: "CRASH_VERDICT_FILE", value: CRASH_VERDICT_FILE_PATH},
            {name: WORKFLOW_SCRIPTS_ROOT_ENV, value: makeStringTypeProxy(args.workflowScriptsRoot)},
        ],
        resources: {
            requests: {cpu: "100m", memory: "128Mi"},
            limits: {cpu: "200m", memory: "256Mi"},
        },
        volumeMounts: [{name: CRASH_VERDICTS_VOLUME_NAME, mountPath: CRASH_VERDICTS_MOUNT_PATH}],
    };
}

/** Each tier sets requests.memory and limits.memory to the same tier value (Guaranteed QoS). */
function tierResourceOverride(
    resources: BaseExpression<ResourceRequirementsType>,
    tierMemoryQuantity: BaseExpression<string>
) {
    const requests = expr.cast(expr.get(resources, "requests")).to<Record<string, string>>();
    const limits = expr.cast(expr.get(resources, "limits")).to<Record<string, string>>();
    return expr.makeDict({
        requests: expr.mergeDicts(requests, expr.makeDict({memory: tierMemoryQuantity})),
        limits: expr.mergeDicts(limits, expr.makeDict({memory: tierMemoryQuantity})),
    });
}

/**
 * The per-tier RFS Deployment: the ordinary RFS worker manifest plus the crash-witness sidecar,
 * the shared verdicts emptyDir, the tier label in the immutable selector / pod labels, the tier
 * env for the worker, the tier's memory quantity (requests == limits), and the raised termination
 * grace period.  Built by mutating the base manifest so the two shapes can never drift apart.
 */
function getRfsTierDeploymentManifest(
    args: Parameters<typeof getRfsDeploymentManifest>[0] & {
        memoryTier: BaseExpression<string>,
        tierMemoryQuantity: BaseExpression<string>,
        consoleImageName: BaseExpression<string>,
        consoleImagePullPolicy: BaseExpression<IMAGE_PULL_POLICY>,
        workflowScriptsRoot: BaseExpression<string>,
    }
): Deployment {
    const crashVerdictsVolume = {name: CRASH_VERDICTS_VOLUME_NAME, emptyDir: {}};
    const crashVerdictsMount = {name: CRASH_VERDICTS_VOLUME_NAME, mountPath: CRASH_VERDICTS_MOUNT_PATH};
    const deployment = getRfsDeploymentManifest({
        ...args,
        fileSourceVolumes: expr.concatArrays(
            expr.templateValue([crashVerdictsVolume]), args.fileSourceVolumes),
        fileSourceVolumeMounts: expr.concatArrays(
            expr.templateValue([crashVerdictsMount]), args.fileSourceVolumeMounts),
    }) as any;

    const tierLabelValue = makeStringTypeProxy(args.memoryTier);
    deployment.metadata.name =
        makeStringTypeProxy(getRfsTierDeploymentName(args.sessionName, args.memoryTier));
    deployment.metadata.labels[RFS_MEMORY_TIER_LABEL] = tierLabelValue;
    deployment.spec.selector.matchLabels[RFS_MEMORY_TIER_LABEL] = tierLabelValue;
    deployment.spec.template.metadata.labels[RFS_MEMORY_TIER_LABEL] = tierLabelValue;
    deployment.spec.template.spec.terminationGracePeriodSeconds = RFS_TERMINATION_GRACE_PERIOD_SECONDS;

    const bulkLoader = deployment.spec.template.spec.containers[0];
    bulkLoader.env.push(
        {name: "RFS_WORKER_MEMORY_TIER", value: tierLabelValue},
        {name: "RFS_CRASH_VERDICT_FILE", value: CRASH_VERDICT_FILE_PATH});
    bulkLoader.resources =
        makeDirectTypeProxy(tierResourceOverride(args.resources, args.tierMemoryQuantity));

    deployment.spec.template.spec.containers.push(getCrashWitnessContainer({
        consoleImageName: args.consoleImageName,
        consoleImagePullPolicy: args.consoleImagePullPolicy,
        workflowScriptsRoot: args.workflowScriptsRoot,
    }));
    return deployment as Deployment;
}

type TierInputExpressions = StartHistoricalBackfillInputExpressions & {
    memoryTier: BaseExpression<string>,
    tierMemoryQuantity: BaseExpression<string>,
    imageMigrationConsoleLocation: BaseExpression<string>,
    imageMigrationConsolePullPolicy: BaseExpression<IMAGE_PULL_POLICY>,
};

function makeRfsTierDeploymentDefinition(
    inputs: TierInputExpressions,
    workflowScriptsRoot: BaseExpression<string>
) {
    return {
        action: "apply" as const,
        setOwnerReference: false,
        manifest: getRfsTierDeploymentManifest({
            podReplicas: expr.deserializeRecord(inputs.podReplicas),
            loggingConfigMap: inputs.loggingConfigurationOverrideConfigMap,
            jvmArgs: inputs.jvmArgs,
            rootLogLevel: inputs.rootLogLevel,
            bulkRequestTraceLogLevel: inputs.bulkRequestTraceLogLevel,
            failedRequestsLogLevel: inputs.failedRequestsLogLevel,
            fileSourceVolumes: expr.deserializeRecord(inputs.fileSourceVolumes),
            fileSourceVolumeMounts: expr.deserializeRecord(inputs.fileSourceVolumeMounts),
            useLocalstackAwsCreds: expr.deserializeRecord(inputs.useLocalStack),
            sessionName: inputs.sessionName,
            targetBasicCredsSecretNameOrEmpty: inputs.targetBasicCredsSecretNameOrEmpty,
            coordinatorBasicCredsSecretNameOrEmpty: inputs.coordinatorBasicCredsSecretNameOrEmpty,
            azureCredsSecretNameOrEmpty: inputs.azureCredsSecretNameOrEmpty,
            rfsImageName: inputs.imageReindexFromSnapshotLocation,
            rfsImagePullPolicy: inputs.imageReindexFromSnapshotPullPolicy,
            workflowName: expr.getWorkflowValue("name"),
            jsonConfig: expr.toBase64YamlSafe(inputs.rfsJsonConfig),
            resources: expr.deserializeRecord(inputs.resources),
            crdName: inputs.crdName,
            crdUid: inputs.crdUid,
            sourceK8sLabel: inputs.sourceK8sLabel,
            targetK8sLabel: inputs.targetK8sLabel,
            snapshotK8sLabel: inputs.snapshotK8sLabel,
            fromSnapshotMigrationK8sLabel: inputs.fromSnapshotMigrationK8sLabel,
            taskK8sLabel: inputs.taskK8sLabel,
            workloadNodePoolName: inputs.workloadNodePoolName,
            workloadZoneSpread: inputs.workloadZoneSpread,
            workloadZoneSpreadExtras: inputs.workloadZoneSpreadExtras,
            memoryTier: inputs.memoryTier,
            tierMemoryQuantity: inputs.tierMemoryQuantity,
            consoleImageName: inputs.imageMigrationConsoleLocation,
            consoleImagePullPolicy: inputs.imageMigrationConsolePullPolicy,
            workflowScriptsRoot,
        })
    };
}

function makeRfsTierPodDisruptionBudgetDefinition(inputs: TierInputExpressions) {
    const labels = {
        [COMPONENT_LABEL]: COMPONENTS.RFS_WORKER,
        [RFS_MEMORY_TIER_LABEL]: inputs.memoryTier,
        "workflows.argoproj.io/workflow": expr.getWorkflowValue("name"),
        "migrations.opensearch.org/source": inputs.sourceK8sLabel,
        "migrations.opensearch.org/target": inputs.targetK8sLabel,
        "migrations.opensearch.org/snapshot": inputs.snapshotK8sLabel,
        "migrations.opensearch.org/from-snapshot-migration": inputs.fromSnapshotMigrationK8sLabel,
        "migrations.opensearch.org/task": inputs.taskK8sLabel,
    };
    const ownerReferences: OwnerReference[] = [{
        apiVersion: "migrations.opensearch.org/v1alpha1",
        kind: "SnapshotMigration",
        name: makeStringTypeProxy(inputs.crdName),
        uid: makeStringTypeProxy(inputs.crdUid),
        controller: false,
        blockOwnerDeletion: true
    }];
    return makePodDisruptionBudgetDefinition({
        name: getRfsTierDeploymentName(inputs.sessionName, inputs.memoryTier),
        minAvailable: workflowParameterAsNumber(inputs.minPodReplicas),
        matchLabels: {
            ...workloadSelectorLabels(COMPONENTS.RFS_WORKER, inputs.crdUid),
            [RFS_MEMORY_TIER_LABEL]: makeStringTypeProxy(inputs.memoryTier),
        },
        labels,
        ownerReferences,
    });
}

export const DocumentBulkLoad = documentBulkLoadBaseBuilder

    .addTemplate("createRfsPodDisruptionBudget", t => t
        .addInputsFromRecord(startHistoricalBackfillInputs)
        .addResourceTask(b => b
            .setDefinition(makeRfsPodDisruptionBudgetDefinition(b.inputs)))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )

    .addTemplate("createRfsDeployment", t => t
        .addInputsFromRecord(startHistoricalBackfillInputs)
        .addResourceTask(b => b
            .setDefinition(makeRfsDeploymentDefinition(b.inputs)))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )

    // Memory-tier variants: one Deployment + PDB per tier (<session>-rfs-t<n>), used only when
    // documentBackfillConfig.memoryScaling is configured. The feature-off templates above are
    // untouched so their rendering stays byte-identical.
    .addTemplate("createRfsTierPodDisruptionBudget", t => t
        .addInputsFromRecord(startHistoricalBackfillInputs)
        .addRequiredInput("memoryTier", typeToken<string>())
        .addRequiredInput("tierMemoryQuantity", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addResourceTask(b => b
            .setDefinition(makeRfsTierPodDisruptionBudgetDefinition(b.inputs)))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )

    .addTemplate("createRfsTierDeployment", t => t
        .addInputsFromRecord(startHistoricalBackfillInputs)
        .addRequiredInput("memoryTier", typeToken<string>())
        .addRequiredInput("tierMemoryQuantity", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addResourceTask(b => b
            .setDefinition(makeRfsTierDeploymentDefinition(
                b.inputs, t.inputs.workflowParameters.workflowScriptsRoot)))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )

    .addTemplate("startTierHistoricalBackfill", t => t
        .addInputsFromRecord(startHistoricalBackfillInputs)
        .addRequiredInput("memoryTier", typeToken<string>())
        .addRequiredInput("tierMemoryQuantity", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addSteps(b => b
            .addStep("createTierPodDisruptionBudget", INTERNAL, "createRfsTierPodDisruptionBudget", c =>
                c.register({...selectInputsForRegister(b, c)}))
            .addStep("createTierDeployment", INTERNAL, "createRfsTierDeployment", c =>
                c.register({...selectInputsForRegister(b, c)}))
        )
    )

    .addTemplate("startHistoricalBackfill", t => t
        .addInputsFromRecord(startHistoricalBackfillInputs)
        .addSteps(b => b
            .addStep("createPodDisruptionBudget", INTERNAL, "createRfsPodDisruptionBudget", c =>
                c.register({...selectInputsForRegister(b, c)}))
            .addStep("createDeployment", INTERNAL, "createRfsDeployment", c =>
                c.register({...selectInputsForRegister(b, c)}))
        )
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
        .addOptionalInput("sourceEndpoint", c => "")
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["ReindexFromSnapshot"]))

        .addSteps(b => b
            .addStep("startHistoricalBackfill", INTERNAL, "startHistoricalBackfill", c => {
                const scaling = scalingFromOptions(b.inputs.documentBackfillConfig);
                return c.register({
                    ...selectInputsForRegister(b, c),
                    podReplicas: scaling.podReplicas,
                    minPodReplicas: scaling.minPodReplicas,
                    targetBasicCredsSecretNameOrEmpty: getHttpAuthSecretName(b.inputs.targetConfig),
                    coordinatorBasicCredsSecretNameOrEmpty: getHttpAuthSecretName(b.inputs.rfsCoordinatorConfig),
                    azureCredsSecretNameOrEmpty: expr.dig(expr.deserializeRecord(b.inputs.snapshotConfig), ["repoConfig", "azureCredentialsSecretName"], ""),
                    loggingConfigurationOverrideConfigMap: expr.dig(expr.deserializeRecord(b.inputs.documentBackfillConfig), ["loggingConfigurationOverrideConfigMap"], ""),
                    jvmArgs: expr.dig(expr.deserializeRecord(b.inputs.documentBackfillConfig), ["jvmArgs"], ""),
                    rootLogLevel: expr.getLoose(expr.getLoose(expr.deserializeRecord(b.inputs.documentBackfillConfig), "logLevels"), "root"),
                    bulkRequestTraceLogLevel: expr.getLoose(expr.getLoose(expr.deserializeRecord(b.inputs.documentBackfillConfig), "logLevels"), "bulkRequestTrace"),
                    failedRequestsLogLevel: expr.getLoose(expr.getLoose(expr.deserializeRecord(b.inputs.documentBackfillConfig), "logLevels"), "failedRequests"),
                    fileSourceVolumes: expr.serialize(expr.dig(expr.deserializeRecord(b.inputs.documentBackfillConfig), ["fileSourceVolumes"], [])),
                    fileSourceVolumeMounts: expr.serialize(expr.dig(expr.deserializeRecord(b.inputs.documentBackfillConfig), ["fileSourceVolumeMounts"], [])),
                    useLocalStack: expr.dig(expr.deserializeRecord(b.inputs.snapshotConfig), ["repoConfig", "useLocalStack"], false),
                    rfsJsonConfig: expr.asString(expr.serialize(
                        makeParamsDict(b.inputs.sourceVersion,
                            b.inputs.targetConfig,
                            b.inputs.rfsCoordinatorConfig,
                            b.inputs.snapshotConfig,
                            b.inputs.documentBackfillConfig,
                            b.inputs.sessionName,
                            b.inputs.crdUid,
                            b.inputs.sourceEndpoint)
                    )),
                    resources: expr.serialize(expr.jsonPathStrict(b.inputs.documentBackfillConfig, "resources")),
                    crdName: b.inputs.crdName,
                    crdUid: b.inputs.crdUid,
                    sourceK8sLabel: b.inputs.sourceLabel,
                    targetK8sLabel: expr.jsonPathStrict(b.inputs.targetConfig, "label"),
                    snapshotK8sLabel: expr.jsonPathStrict(b.inputs.snapshotConfig, "label"),
                    fromSnapshotMigrationK8sLabel: b.inputs.migrationLabel
                });
            })
        )
    )


    // Feature-on analogue of startHistoricalBackfillFromConfig: creates the TIER-0 runtime
    // (crash-witness sidecar, tier label/env, raised grace period) with replicas bootstrapped
    // at 1 — the monitor's first sweep after the shard_setup sentinel right-sizes the fleet.
    .addTemplate("startTierZeroBackfillFromConfig", t => t
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
        .addOptionalInput("sourceEndpoint", c => "")
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["ReindexFromSnapshot", "MigrationConsole"]))

        .addSteps(b => b
            .addStep("startTierHistoricalBackfill", INTERNAL, "startTierHistoricalBackfill", c => {
                const scaling = scalingFromOptions(b.inputs.documentBackfillConfig);
                const backfillConfig = expr.deserializeRecord(b.inputs.documentBackfillConfig);
                const tierTable = expr.cast(
                    expr.getLoose(backfillConfig, "memoryScalingTierTable")).to<string[]>();
                return c.register({
                    ...selectInputsForRegister(b, c),
                    memoryTier: expr.literal("0"),
                    tierMemoryQuantity: expr.index(tierTable, expr.literal(0)),
                    // Bootstrap: level 0 starts at one pod; the monitor right-sizes after setup.
                    podReplicas: expr.literal(1),
                    minPodReplicas: scaling.minPodReplicas,
                    targetBasicCredsSecretNameOrEmpty: getHttpAuthSecretName(b.inputs.targetConfig),
                    coordinatorBasicCredsSecretNameOrEmpty: getHttpAuthSecretName(b.inputs.rfsCoordinatorConfig),
                    azureCredsSecretNameOrEmpty: expr.dig(expr.deserializeRecord(b.inputs.snapshotConfig), ["repoConfig", "azureCredentialsSecretName"], ""),
                    loggingConfigurationOverrideConfigMap: expr.dig(backfillConfig, ["loggingConfigurationOverrideConfigMap"], ""),
                    jvmArgs: expr.dig(backfillConfig, ["jvmArgs"], ""),
                    rootLogLevel: expr.getLoose(expr.getLoose(backfillConfig, "logLevels"), "root"),
                    bulkRequestTraceLogLevel: expr.getLoose(expr.getLoose(backfillConfig, "logLevels"), "bulkRequestTrace"),
                    failedRequestsLogLevel: expr.getLoose(expr.getLoose(backfillConfig, "logLevels"), "failedRequests"),
                    fileSourceVolumes: expr.serialize(expr.dig(backfillConfig, ["fileSourceVolumes"], [])),
                    fileSourceVolumeMounts: expr.serialize(expr.dig(backfillConfig, ["fileSourceVolumeMounts"], [])),
                    useLocalStack: expr.dig(expr.deserializeRecord(b.inputs.snapshotConfig), ["repoConfig", "useLocalStack"], false),
                    rfsJsonConfig: expr.asString(expr.serialize(
                        makeParamsDict(b.inputs.sourceVersion,
                            b.inputs.targetConfig,
                            b.inputs.rfsCoordinatorConfig,
                            b.inputs.snapshotConfig,
                            b.inputs.documentBackfillConfig,
                            b.inputs.sessionName,
                            b.inputs.crdUid,
                            b.inputs.sourceEndpoint)
                    )),
                    resources: expr.serialize(expr.jsonPathStrict(b.inputs.documentBackfillConfig, "resources")),
                    crdName: b.inputs.crdName,
                    crdUid: b.inputs.crdUid,
                    sourceK8sLabel: b.inputs.sourceLabel,
                    targetK8sLabel: expr.jsonPathStrict(b.inputs.targetConfig, "label"),
                    snapshotK8sLabel: expr.jsonPathStrict(b.inputs.snapshotConfig, "label"),
                    fromSnapshotMigrationK8sLabel: b.inputs.migrationLabel
                });
            })
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

    .addTemplate("approveBackfill", t => t
        .addRequiredInput("name", typeToken<string>())
        .addSteps(b => b
            .addStep("waitForUserApproval", ResourceManagement, "waitForUserApproval", c =>
                c.register({resourceName: b.inputs.name}))
        )
    )

    .addTemplate("doNothing", t => t
        .addSteps(b => b.addStepGroup(c => c)))

    // NOTE: the setup-and-run orchestration (console config, monitor CronJob, coordinator,
    // runtime creation, CR wait) moved to rfsBackfill.ts (the RfsBackfill WorkflowTemplate),
    // which owns RFS lifecycle setup. This file keeps the manifest builders and the
    // config-to-inputs templates it calls.

    .getFullScope();
