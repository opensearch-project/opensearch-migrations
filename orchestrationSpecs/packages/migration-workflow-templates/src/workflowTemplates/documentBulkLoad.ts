import {z} from "zod";
import {
    CLUSTER_VERSION_STRING,
    COMPLETE_SNAPSHOT_CONFIG,
    CONSOLE_SERVICES_CONFIG_FILE,
    NAMED_TARGET_CLUSTER_CONFIG,
    ResourceRequirementsType,
    RFS_OPTIONS
} from "@opensearch-migrations/schemas";
import {MigrationConsole} from "./migrationConsole";

import {
    AllowLiteralOrExpression,
    BaseExpression,
    expr,
    ExpressionType,
    FunctionExpression,
    IMAGE_PULL_POLICY,
    INTERNAL, makeDirectTypeProxy, makeStringTypeProxy,
    selectInputsForRegister,
    Serialized,
    typeToken,
    WorkflowBuilder
} from "@opensearch-migrations/argo-workflow-builders";
import {Deployment} from "@opensearch-migrations/argo-workflow-builders";
import {makeRepoParamDict, makeMountpointRepoParamDict, S3_MOUNT_PATH} from "./metadataMigration";
import {
    setupLog4jConfigForContainer,
    setupTestCredsForContainer,
    setupS3CsiVolumeForContainer
} from "./commonUtils/containerFragments";
import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";
import {makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";
import {makeTargetParamDict, makeRfsCoordinatorParamDict} from "./commonUtils/clusterSettingManipulators";
import {getHttpAuthSecretName} from "./commonUtils/clusterSettingManipulators";
import {RfsCoordinatorCluster, getRfsCoordinatorClusterName, makeRfsCoordinatorConfig} from "./rfsCoordinatorCluster";

function shouldCreateRfsWorkCoordinationCluster(
    documentBackfillConfig: BaseExpression<Serialized<z.infer<typeof RFS_OPTIONS>>>
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
    options: BaseExpression<Serialized<z.infer<typeof RFS_OPTIONS>>>,
    sessionName: BaseExpression<string>
) {
    return expr.mergeDicts(
        expr.mergeDicts(
            expr.mergeDicts(
                makeTargetParamDict(targetConfig),
                makeRfsCoordinatorParamDict(rfsCoordinatorConfig)
            ),
            expr.omit(expr.deserializeRecord(options), "loggingConfigurationOverrideConfigMap", "podReplicas", "resources", "useTargetClusterForWorkCoordination")
        ),
        expr.mergeDicts(
            expr.makeDict({
                snapshotName: expr.get(expr.deserializeRecord(snapshotConfig), "snapshotName"),
                sourceVersion: sourceVersion,
                sessionName: sessionName,
                luceneDir: "/tmp",
                cleanLocalDirs: true
            }),
            makeMountpointRepoParamDict(
                expr.omit(expr.get(expr.deserializeRecord(snapshotConfig), "repoConfig"), "s3RoleArn"))
        )
    );
}

function getRfsDeploymentName(sessionName: BaseExpression<string>) {
    return expr.concat(sessionName, expr.literal("-rfs"));
}

/**
 * Extract the S3 bucket name from an s3:// URI.
 * e.g. s3://my-bucket/path/to/snapshot → my-bucket
 */
function extractBucketName(s3RepoPathUri: BaseExpression<string>) {
    return new FunctionExpression<string, string, ExpressionType, "complicatedExpression">(
        "sprig.regexReplaceAll",
        [expr.literal("^s3://([^/]+).*$"), s3RepoPathUri, expr.literal("${1}")] as any);
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

    rfsImageName: BaseExpression<string>,
    rfsImagePullPolicy: BaseExpression<IMAGE_PULL_POLICY>,
    resources: BaseExpression<ResourceRequirementsType>,

    s3BucketName: BaseExpression<string>,

    sourceK8sLabel: BaseExpression<string>,
    targetK8sLabel: BaseExpression<string>,
    snapshotK8sLabel: BaseExpression<string>,
    fromSnapshotMigrationK8sLabel: BaseExpression<string>,
    taskK8sLabel: BaseExpression<string>
}): Deployment {
    const targetBasicCredsSecretName = expr.ternary(
        expr.isEmpty(args.targetBasicCredsSecretNameOrEmpty),
        expr.literal("empty"),
        args.targetBasicCredsSecretNameOrEmpty
    );
    const coordinatorBasicCredsSecretName = expr.ternary(
        expr.isEmpty(args.coordinatorBasicCredsSecretNameOrEmpty),
        expr.literal("empty"),
        args.coordinatorBasicCredsSecretNameOrEmpty
    );
    const useCustomLogging = expr.not(expr.isEmpty(args.loggingConfigMap));
    const baseContainerDefinition = {
        name: "bulk-loader",
        image: makeStringTypeProxy(args.rfsImageName),
        imagePullPolicy: makeStringTypeProxy(args.rfsImagePullPolicy),
        command: ["/rfs-app/runJavaWithClasspathWithRepeat.sh"],
        env: [
            // see getTargetHttpAuthCreds() - it's very similar, but for a raw K8s container, we pass
            // environment variables as a list, as K8s expects them.  The getTargetHttpAuthCreds()
            // returns them in a key-value format that the ContainerBuilder uses, which is converted
            // by the argoResourceRenderer.  It would be a nice idea to unify this format with the
            // container builder's, but it's probably a much bigger lift than it seems since we're
            // type checking this object against the k8s schema below.
            //
            // I could also use getTargetHttpAuthCreds to create the partial values, then substitute
            // those into here by splicing.  Writing a generic splicer isn't that straightforward since
            // there are a few other inconsistencies between the manifest and argo-container definitions.
            // As of now, we only have this block (though a couple others will come about too) and it
            // doesn't seem like it's worth the complexity.  There's some readability value to having
            // less normalization here as it benefits readability.
            {
                name: "TARGET_USERNAME",
                valueFrom: {
                    secretKeyRef: {
                        name: makeStringTypeProxy(targetBasicCredsSecretName),
                        key: "username",
                        optional: true
                    }
                }
            },
            {
                name: "TARGET_PASSWORD",
                valueFrom: {
                    secretKeyRef: {
                        name: makeStringTypeProxy(targetBasicCredsSecretName),
                        key: "password",
                        optional: true
                    }
                }
            },
            {
                name: "COORDINATOR_USERNAME",
                valueFrom: {
                    secretKeyRef: {
                        name: makeStringTypeProxy(coordinatorBasicCredsSecretName),
                        key: "username",
                        optional: true
                    }
                }
            },
            {
                name: "COORDINATOR_PASSWORD",
                valueFrom: {
                    secretKeyRef: {
                        name: makeStringTypeProxy(coordinatorBasicCredsSecretName),
                        key: "password",
                        optional: true
                    }
                }
            },
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

    const finalContainerDefinition= setupTestCredsForContainer(
        args.useLocalstackAwsCreds,
        setupS3CsiVolumeForContainer(
            S3_MOUNT_PATH,
            args.s3BucketName,
            setupLog4jConfigForContainer(
                useCustomLogging,
                args.loggingConfigMap,
                { container: baseContainerDefinition, volumes: [], sidecars: []}
            )
        )
    );
    const deploymentName = getRfsDeploymentName(args.sessionName);
    return {
        apiVersion: "apps/v1",
        kind: "Deployment",
        metadata: {
            name: makeStringTypeProxy(deploymentName),
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
                    containers: [finalContainerDefinition.container, ...finalContainerDefinition.sidecars],
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

export const DocumentBulkLoad = WorkflowBuilder.create({
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
        ))


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
        .addOptionalInput("groupName", c => "checks")
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
        .addRequiredInput("loggingConfigurationOverrideConfigMap", typeToken<string>())
        .addRequiredInput("useLocalStack", typeToken<boolean>(), "Only used for local testing")
        .addRequiredInput("resources", typeToken<ResourceRequirementsType>())
        .addRequiredInput("s3BucketName", typeToken<string>())
        .addRequiredInput("sourceK8sLabel", typeToken<string>())
        .addRequiredInput("targetK8sLabel", typeToken<string>())
        .addRequiredInput("snapshotK8sLabel", typeToken<string>())
        .addRequiredInput("fromSnapshotMigrationK8sLabel", typeToken<string>())
        .addOptionalInput("taskK8sLabel", c => "reindexFromSnapshot")
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["ReindexFromSnapshot"]))

        .addResourceTask(b => b
            .setDefinition({
                action: "create",
                setOwnerReference: true,
                manifest: getRfsDeploymentManifest({
                    podReplicas: expr.deserializeRecord(b.inputs.podReplicas),
                    loggingConfigMap: b.inputs.loggingConfigurationOverrideConfigMap,
                    useLocalstackAwsCreds: expr.deserializeRecord(b.inputs.useLocalStack),
                    sessionName: b.inputs.sessionName,
                    targetBasicCredsSecretNameOrEmpty: b.inputs.targetBasicCredsSecretNameOrEmpty,
                    coordinatorBasicCredsSecretNameOrEmpty: b.inputs.coordinatorBasicCredsSecretNameOrEmpty,
                    rfsImageName: b.inputs.imageReindexFromSnapshotLocation,
                    rfsImagePullPolicy: b.inputs.imageReindexFromSnapshotPullPolicy,
                    workflowName: expr.getWorkflowValue("name"),
                    jsonConfig: expr.toBase64(b.inputs.rfsJsonConfig),
                    resources: expr.deserializeRecord(b.inputs.resources),
                    s3BucketName: b.inputs.s3BucketName,
                    sourceK8sLabel: b.inputs.sourceK8sLabel,
                    targetK8sLabel: b.inputs.targetK8sLabel,
                    snapshotK8sLabel: b.inputs.snapshotK8sLabel,
                    fromSnapshotMigrationK8sLabel: b.inputs.fromSnapshotMigrationK8sLabel,
                    taskK8sLabel: b.inputs.taskK8sLabel,
                })
            }))
    )


    .addTemplate("startHistoricalBackfillFromConfig", t => t
        .addRequiredInput("sessionName", typeToken<string>())
        .addRequiredInput("sourceVersion", typeToken<z.infer<typeof CLUSTER_VERSION_STRING>>())
        .addRequiredInput("sourceLabel", typeToken<string>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("rfsCoordinatorConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("documentBackfillConfig", typeToken<z.infer<typeof RFS_OPTIONS>>())
        .addRequiredInput("migrationLabel", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["ReindexFromSnapshot"]))

        .addSteps(b => b
            .addStep("startHistoricalBackfill", INTERNAL, "startHistoricalBackfill", c =>
                c.register({
                    ...selectInputsForRegister(b,c),
                    podReplicas: expr.dig(expr.deserializeRecord(b.inputs.documentBackfillConfig), ["podReplicas"], 1),
                    targetBasicCredsSecretNameOrEmpty: getHttpAuthSecretName(b.inputs.targetConfig),
                    coordinatorBasicCredsSecretNameOrEmpty: getHttpAuthSecretName(b.inputs.rfsCoordinatorConfig),
                    loggingConfigurationOverrideConfigMap: expr.dig(expr.deserializeRecord(b.inputs.documentBackfillConfig), ["loggingConfigurationOverrideConfigMap"], ""),
                    useLocalStack: expr.dig(expr.deserializeRecord(b.inputs.snapshotConfig), ["repoConfig", "useLocalStack"], false),
                    rfsJsonConfig: expr.asString(expr.serialize(
                        makeParamsDict(b.inputs.sourceVersion,
                            b.inputs.targetConfig,
                            b.inputs.rfsCoordinatorConfig,
                            b.inputs.snapshotConfig,
                            b.inputs.documentBackfillConfig,
                            b.inputs.sessionName)
                    )),
                    resources: expr.serialize(expr.jsonPathStrict(b.inputs.documentBackfillConfig, "resources")),
                    s3BucketName: extractBucketName(expr.get(expr.get(expr.deserializeRecord(b.inputs.snapshotConfig), "repoConfig"), "s3RepoPathUri")),
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
        .addOptionalInput("indices", c => [] as readonly string[])
        .addRequiredInput("documentBackfillConfig", typeToken<z.infer<typeof RFS_OPTIONS>>())
        .addRequiredInput("migrationLabel", typeToken<string>())
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
        .addOptionalInput("indices", c => [] as readonly string[])
        .addRequiredInput("documentBackfillConfig", typeToken<z.infer<typeof RFS_OPTIONS>>())
        .addRequiredInput("migrationLabel", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["ReindexFromSnapshot", "MigrationConsole"]))

        .addSteps(b => {
            const createRfsCluster = shouldCreateRfsWorkCoordinationCluster(b.inputs.documentBackfillConfig);
            return b
                // (conditional) Deploy an OpenSearch cluster for RFS work coordination
                .addStep("createRfsCoordinator", RfsCoordinatorCluster, "createRfsCoordinator", c =>
                    c.register({
                        clusterName: getRfsCoordinatorClusterName(b.inputs.sessionName)
                    }),
                    { when: { templateExp: createRfsCluster }}
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
                    { when: { templateExp: createRfsCluster }}
                );
        })
    )

    .getFullScope();
