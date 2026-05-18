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
    getTransformsPresence,
    setupLog4jConfigForContainer,
    setupTestCredsForContainer,
    setupTransformsForContainerForMode,
    TransformVolumeMode
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

const startHistoricalBackfillInputs = {
    sessionName: defineRequiredParam<string>(),
    rfsJsonConfig: defineRequiredParam<string>(),
    targetBasicCredsSecretNameOrEmpty: defineRequiredParam<string>(),
    coordinatorBasicCredsSecretNameOrEmpty: defineRequiredParam<string>(),
    podReplicas: defineRequiredParam<number>(),
    jvmArgs: defineRequiredParam<string>(),
    loggingConfigurationOverrideConfigMap: defineRequiredParam<string>(),
    transformsImage: defineRequiredParam<string>(),
    transformsImagePullPolicy: defineRequiredParam<IMAGE_PULL_POLICY>(),
    transformsConfigMap: defineRequiredParam<string>(),
    useLocalStack: defineRequiredParam<boolean>({description: "Only used for local testing"}),
    resources: defineRequiredParam<ResourceRequirementsType>(),
    crdName: defineRequiredParam<string>(),
    crdUid: defineRequiredParam<string>(),
    sourceK8sLabel: defineRequiredParam<string>(),
    targetK8sLabel: defineRequiredParam<string>(),
    snapshotK8sLabel: defineRequiredParam<string>(),
    fromSnapshotMigrationK8sLabel: defineRequiredParam<string>(),
    taskK8sLabel: defineParam<string>({expression: expr.literal("reindexFromSnapshot")}),
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

    useLocalstackAwsCreds: BaseExpression<boolean>,
    loggingConfigMap: BaseExpression<string>,
    jvmArgs: BaseExpression<string>,
    transformsImage: BaseExpression<string>,
    transformsImagePullPolicy: BaseExpression<IMAGE_PULL_POLICY>,
    transformsConfigMap: BaseExpression<string>,
    transformsVolumeMode: TransformVolumeMode,

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

    const finalContainerDefinition = setupTransformsForContainerForMode(
        args.transformsVolumeMode,
        args.transformsImage,
        args.transformsImagePullPolicy,
        args.transformsConfigMap,
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

status_json=$(console --config-file=/config/migration_services.yaml --json backfill status --deep-check)
status=$(echo "$status_json" | jq -r '.status')

# Check if initializing
if [[ "$status" == "Pending" ]]; then
    echo "Shards are initializing" > /tmp/status-output.txt
else
    eval "$(echo "$status_json" | jq -r '
      @sh "pct=\(.percentage_completed // 0)
      eta=\(if .eta_ms == null then "unknown" else "\(.eta_ms / 1000 | floor)s" end)
      progress=\(.shard_in_progress // 0)
      waiting=\(.shard_waiting // 0)
      complete=\(.shard_complete // 0)
      total=\(.shard_total // 0)"
    ')"
    printf "complete: %.2f%%, ETA: %s; shards in-progress: %d; remaining: %d; shards complete/total: %d/%d\\n" \
           "$pct" "$eta" "$progress" "$waiting" "$complete" "$total" > /tmp/status-output.txt
fi

# Check completion status - exit 0 only if complete, otherwise exit 1
if [[ "$status" == "Completed" ]]; then
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
    inputs: StartHistoricalBackfillInputExpressions,
    transformsVolumeMode: TransformVolumeMode
) {
    return {
        action: "create" as const,
        setOwnerReference: false,
        manifest: getRfsDeploymentManifest({
            podReplicas: expr.deserializeRecord(inputs.podReplicas),
            loggingConfigMap: inputs.loggingConfigurationOverrideConfigMap,
            jvmArgs: inputs.jvmArgs,
            transformsImage: inputs.transformsImage,
            transformsImagePullPolicy: inputs.transformsImagePullPolicy,
            transformsConfigMap: inputs.transformsConfigMap,
            transformsVolumeMode,
            useLocalstackAwsCreds: expr.deserializeRecord(inputs.useLocalStack),
            sessionName: inputs.sessionName,
            targetBasicCredsSecretNameOrEmpty: inputs.targetBasicCredsSecretNameOrEmpty,
            coordinatorBasicCredsSecretNameOrEmpty: inputs.coordinatorBasicCredsSecretNameOrEmpty,
            rfsImageName: inputs.imageReindexFromSnapshotLocation,
            rfsImagePullPolicy: inputs.imageReindexFromSnapshotPullPolicy,
            workflowName: expr.getWorkflowValue("name"),
            jsonConfig: expr.toBase64(inputs.rfsJsonConfig),
            resources: expr.deserializeRecord(inputs.resources),
            crdName: inputs.crdName,
            crdUid: inputs.crdUid,
            sourceK8sLabel: inputs.sourceK8sLabel,
            targetK8sLabel: inputs.targetK8sLabel,
            snapshotK8sLabel: inputs.snapshotK8sLabel,
            fromSnapshotMigrationK8sLabel: inputs.fromSnapshotMigrationK8sLabel,
            taskK8sLabel: inputs.taskK8sLabel,
        })
    };
}

function addDocumentBulkLoadTransformTemplates(builder: typeof documentBulkLoadBaseBuilder) {
    return builder
        .addTemplate("startHistoricalBackfillWithImageTransforms", t => t
            .addInputsFromRecord(startHistoricalBackfillInputs)
            .addResourceTask(b => b
                .setDefinition(makeRfsDeploymentDefinition(b.inputs, "image")))
            .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
        )
        .addTemplate("startHistoricalBackfillWithConfigMapTransforms", t => t
            .addInputsFromRecord(startHistoricalBackfillInputs)
            .addResourceTask(b => b
                .setDefinition(makeRfsDeploymentDefinition(b.inputs, "configMap")))
            .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
        )
        .addTemplate("startHistoricalBackfillNoTransforms", t => t
            .addInputsFromRecord(startHistoricalBackfillInputs)
            .addResourceTask(b => b
                .setDefinition(makeRfsDeploymentDefinition(b.inputs, "emptyDir")))
            .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
        );
}

export const DocumentBulkLoad = addDocumentBulkLoadTransformTemplates(documentBulkLoadBaseBuilder)

    .addTemplate("startHistoricalBackfill", t => t
        .addInputsFromRecord(startHistoricalBackfillInputs)
        .addSteps(b => {
            const {hasImage, hasConfigMapOnly, hasNone} =
                getTransformsPresence(b.inputs.transformsImage, b.inputs.transformsConfigMap);

            return b
                .addStep("withImageTransforms", INTERNAL, "startHistoricalBackfillWithImageTransforms", c =>
                    c.register({...selectInputsForRegister(b, c), taskK8sLabel: b.inputs.taskK8sLabel}),
                    {when: {templateExp: hasImage}}
                )
                .addStep("withConfigMapTransforms", INTERNAL, "startHistoricalBackfillWithConfigMapTransforms", c =>
                    c.register({...selectInputsForRegister(b, c), taskK8sLabel: b.inputs.taskK8sLabel}),
                    {when: {templateExp: hasConfigMapOnly}}
                )
                .addStep("withoutTransforms", INTERNAL, "startHistoricalBackfillNoTransforms", c =>
                    c.register({...selectInputsForRegister(b, c), taskK8sLabel: b.inputs.taskK8sLabel}),
                    {when: {templateExp: hasNone}}
                );
        })
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
                    transformsImage: expr.dig(expr.deserializeRecord(b.inputs.documentBackfillConfig), ["transformsImage"], ""),
                    transformsImagePullPolicy: expr.get(
                        expr.deserializeRecord(b.inputs.documentBackfillConfig),
                        "transformsImagePullPolicy"
                    ),
                    transformsConfigMap: expr.dig(expr.deserializeRecord(b.inputs.documentBackfillConfig), ["transformsConfigMap"], ""),
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
