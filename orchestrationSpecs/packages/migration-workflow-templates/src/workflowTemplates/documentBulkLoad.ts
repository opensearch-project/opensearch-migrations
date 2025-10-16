import {
    CommonWorkflowParameters,
    extractTargetKeysToExpressionMap,
    makeRequiredImageParametersForKeys,
    setupLog4jConfigForContainer,
    setupTestCredsForContainer,
    TargetClusterParameters
} from "./commonWorkflowTemplates";
import {z} from "zod";
import {
    COMPLETE_SNAPSHOT_CONFIG,
    CONSOLE_SERVICES_CONFIG_FILE, getZodKeys,
    RFS_OPTIONS,
    TARGET_CLUSTER_CONFIG
} from "@opensearch-migrations/schemas";
import {MigrationConsole} from "./migrationConsole";

import {
    BaseExpression,
    expr,
    IMAGE_PULL_POLICY,
    inputsToEnvVarsList,
    INTERNAL,
    MISSING_FIELD,
    selectInputsFieldsAsExpressionRecord,
    selectInputsForRegister,
    transformZodObjectToParams,
    typeToken,
    WorkflowBuilder
} from "@opensearch-migrations/argo-workflow-builders";


function getRfsReplicasetManifest
(args: {
    workflowName: BaseExpression<string>,
    sessionName: BaseExpression<string>,
    podReplicas: BaseExpression<number>,

    useLocalstackAwsCreds: BaseExpression<boolean>,
    loggingConfigMap: BaseExpression<string>,
    useCustomLogging: BaseExpression<boolean>,

    rfsImageName: BaseExpression<string>,
    rfsImagePullPolicy: BaseExpression<IMAGE_PULL_POLICY>,
    inputsAsEnvList: Record<string, any>[],
}) {
    const baseContainerDefinition = {
        name: "bulk-loader",
        image: args.rfsImageName,
        imagePullPolicy: args.rfsImagePullPolicy,
        env: [
            ...args.inputsAsEnvList,
            {name: "LUCENE_DIR", value: expr.literal("/tmp")}
        ]
    };
    const finalContainerDefinition =
        setupTestCredsForContainer(args.useLocalstackAwsCreds,
            setupLog4jConfigForContainer(args.useCustomLogging, args.loggingConfigMap,
                { container: baseContainerDefinition, volumes: []}));
    return {
        apiVersion: "apps/v1",
        kind: "ReplicaSet",
        metadata: {
            name: expr.concat(args.sessionName, expr.literal("-reindex-from-snapshot")),
            labels: {
                "workflows.argoproj.io/workflow": args.workflowName
            },
        },
        spec: {
            replicas: args.podReplicas,
            selector: {
                matchLabels: {
                    app: "bulk-loader",
                },
            },
            template: {
                metadata: {
                    labels: {
                        app: "bulk-loader",
                        "workflows.argoproj.io/workflow": args.workflowName,
                    },
                },
                spec: {
                    containers: [finalContainerDefinition.container],
                    volumes: finalContainerDefinition.volumes
                },
            },
        }
    }
}


function getCheckRfsCompletionScript(sessionName: BaseExpression<string>) {
    const template = `
set -x && 
python -c '
import sys
from lib.console_link.console_link.environment import Environment
from lib.console_link.console_link.models.backfill_rfs import get_detailed_status_dict
from lib.console_link.console_link.models.backfill_rfs import all_shards_finished_processing

status = get_detailed_status_dict(Environment("/config/migration_services.yaml").target_cluster, 
                                  "{{SESSION_NAME}}")
print(status)
all_finished = all_shards_finished_processing(Environment("/config/migration_services.yaml").target_cluster,
                                              "{{SESSION_NAME}}")
sys.exit(0 if all_finished else 1)`;
    return expr.fillTemplate(template, {"SESSION_NAME": sessionName});
}

export const DocumentBulkLoad = WorkflowBuilder.create({
    k8sResourceName: "document-bulk-load",
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)


    .addTemplate("deleteReplicaSet", t => t
        .addRequiredInput("name", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "delete", flags: ["--ignore-not-found"],
                manifest: {
                    "apiVersion": "apps/v1",
                    "kind": "ReplicaSet",
                    "metadata": {
                        "name": expr.concat(expr.literal("bulk-loader-"), b.inputs.name)
                    }
                }
            })
        ))


    .addTemplate("waitForCompletion", t => t
        .addRequiredInput("configContents", typeToken<z.infer<typeof CONSOLE_SERVICES_CONFIG_FILE>>())
        .addRequiredInput("sessionName", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addSteps(b => b
            .addStep("checkRfsCompletion", MigrationConsole, "runMigrationCommand", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    command: getCheckRfsCompletionScript(b.inputs.sessionName)
                }))
        )
        .addRetryParameters({
            limit: "200",
            retryPolicy: "Always",
            backoff: {duration: "5", factor: "2", maxDuration: "300"}
        })
    )


    .addTemplate("createReplicaset", t => t
        .addRequiredInput("sessionName", typeToken<string>())
        .addRequiredInput("useLocalStack", typeToken<boolean>(), "Only used for local testing")

        .addRequiredInput("snapshotName", typeToken<string>())
        .addRequiredInput("snapshotRepoPath", typeToken<string>())
        .addRequiredInput("s3Endpoint", typeToken<string>())
        .addRequiredInput("s3Region", typeToken<string>())

        .addInputsFromRecord(TargetClusterParameters)
        .addInputsFromRecord(transformZodObjectToParams(RFS_OPTIONS))
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["ReindexFromSnapshot"]))

        .addResourceTask(b => b
            .setDefinition({
                action: "create",
                setOwnerReference: true,
                manifest: getRfsReplicasetManifest({
                    podReplicas: b.inputs.podReplicas,
                    loggingConfigMap: b.inputs.loggingConfigurationOverrideConfigMap,
                    useCustomLogging:
                        expr.equals(expr.literal(""),
                            expr.nullCoalesce(b.inputs.loggingConfigurationOverrideConfigMap, expr.literal(""))),
                    useLocalstackAwsCreds: b.inputs.useLocalStack,
                    sessionName: b.inputs.sessionName,
                    rfsImageName: b.inputs.imageReindexFromSnapshotLocation,
                    rfsImagePullPolicy: b.inputs.imageReindexFromSnapshotPullPolicy,
                    inputsAsEnvList: [
                        ...inputsToEnvVarsList({...b.inputs}, "JCOMMANDER")
                    ],
                    workflowName: expr.getWorkflowValue("name")
                })
            }))
    )


    .addTemplate("createReplicasetFromConfig", t => t
        .addRequiredInput("sessionName", typeToken<string>())

        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof TARGET_CLUSTER_CONFIG>>())

        .addRequiredInput("documentBackfillConfig", typeToken<z.infer<typeof RFS_OPTIONS>>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["ReindexFromSnapshot"]))

        .addSteps(b => b
            .addStep("createReplicaset", INTERNAL, "createReplicaset", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    ...selectInputsFieldsAsExpressionRecord(expr.deserializeRecord(b.inputs.documentBackfillConfig), c,
                        getZodKeys(RFS_OPTIONS)),
                    ...(extractTargetKeysToExpressionMap(b.inputs.targetConfig)),

                    s3Endpoint: expr.dig(expr.deserializeRecord(b.inputs.snapshotConfig), ["repoConfig", "endpoint"], ""),
                    s3Region: expr.dig(expr.deserializeRecord(b.inputs.snapshotConfig), ["repoConfig", "aws_region"], ""),
                    snapshotName: expr.dig(expr.deserializeRecord(b.inputs.snapshotConfig), ["snapshotName"], ""),
                    snapshotRepoPath: expr.jsonPathStrict(b.inputs.snapshotConfig, "repoConfig", "s3RepoPathUri"),
                    useLocalStack: expr.dig(expr.deserializeRecord(b.inputs.snapshotConfig), ["repoConfig", "useLocalStack"], false)
                })))
    )


    .addTemplate("runBulkLoad", t => t
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("sessionName", typeToken<string>())
        .addOptionalInput("indices", c => [] as readonly string[])
        .addRequiredInput("documentBackfillConfig", typeToken<z.infer<typeof RFS_OPTIONS>>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["ReindexFromSnapshot", "MigrationConsole"]))

        .addSteps(b => b
            .addStep("createReplicasetFromConfig", INTERNAL, "createReplicasetFromConfig", c =>
                c.register({
                    ...selectInputsForRegister(b, c)
                }))
            .addStep("setupWaitForCompletion", MigrationConsole, "getConsoleConfig", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    kafkaInfo: MISSING_FIELD,
                    sourceConfig: MISSING_FIELD
                }))
            .addStep("waitForCompletion", INTERNAL, "waitForCompletion", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    configContents: c.steps.setupWaitForCompletion.outputs.configContents
                }))
            .addStep("deleteReplicaSet", INTERNAL, "deleteReplicaSet", c =>
                c.register({name: b.inputs.sessionName}))
        )
    )

    .getFullScope();
