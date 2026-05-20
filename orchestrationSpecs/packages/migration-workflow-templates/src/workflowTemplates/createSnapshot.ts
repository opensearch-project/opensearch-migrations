import {z} from "zod";
import {
    ARGO_CREATE_SNAPSHOT_WORKFLOW_OPTION_KEYS,
    ARGO_CREATE_SNAPSHOT_OPTIONS,
    CLUSTER_CONFIG,
    COMPLETE_SNAPSHOT_CONFIG,
    CONSOLE_SERVICES_CONFIG_FILE,
    DEFAULT_RESOURCES,
    NAMED_SOURCE_CLUSTER_CONFIG_WITHOUT_SNAPSHOT_INFO,
} from "@opensearch-migrations/schemas";
import {MigrationConsole} from "./migrationConsole";
import {ResourceManagement} from "./resourceManagement";
import {
    BaseExpression,
    expr,
    INTERNAL,
    selectInputsForRegister, Serialized,
    typeToken,
    WorkflowBuilder
} from "@opensearch-migrations/argo-workflow-builders";
import {makeRepoParamDict} from "./metadataMigration";

import {CommonWorkflowParameters, workflowIdentityEnvVars, workflowScriptPath} from "./commonUtils/workflowParameters";
import {makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";
import {makeClusterParamDict} from "./commonUtils/clusterSettingManipulators";
import {getHttpAuthSecretName} from "./commonUtils/clusterSettingManipulators";
import {getSourceHttpAuthCreds} from "./commonUtils/basicCredsGetters";
import {CONTAINER_TEMPLATE_RETRY_STRATEGY} from "./commonUtils/resourceRetryStrategy";

function getSnapshotDoneCronJobName(dataSnapshotName: BaseExpression<string>) {
    return expr.concat(dataSnapshotName, expr.literal("-snapshot-done"));
}

const SNAPSHOT_MONITOR_WORKFLOW_UID_LABEL = "migrations.opensearch.org/snapshot-monitor-workflow-uid";
const SNAPSHOT_MONITOR_SESSION_LABEL = "migrations.opensearch.org/snapshot-monitor-session";

export function makeSourceParamDict(sourceConfig: BaseExpression<Serialized<z.infer<typeof CLUSTER_CONFIG>>>) {
    return makeClusterParamDict("source", sourceConfig);
}

function makeParamsDict(
    sourceConfig: BaseExpression<Serialized<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG_WITHOUT_SNAPSHOT_INFO>>>,
    snapshotConfig: BaseExpression<Serialized<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>>,
    options: BaseExpression<Serialized<z.infer<typeof ARGO_CREATE_SNAPSHOT_OPTIONS>>>
) {
    return expr.mergeDicts(
        expr.mergeDicts(
            makeSourceParamDict(sourceConfig),
            expr.mergeDicts(
                expr.omit(expr.deserializeRecord(options), ...ARGO_CREATE_SNAPSHOT_WORKFLOW_OPTION_KEYS),
                // noWait is essential for workflow logic - the workflow handles polling for snapshot
                // completion separately via checkSnapshotStatus, so the CreateSnapshot command must
                // return immediately to allow the workflow to manage the wait/retry behavior
                expr.makeDict({
                    "noWait": expr.literal(true)
                })
            )
        ),
        expr.mergeDicts(
            expr.makeDict({
                "snapshotName": expr.get(expr.deserializeRecord(snapshotConfig), "snapshotName"),
                "snapshotRepoName": expr.jsonPathStrict(snapshotConfig, "repoConfig", "repoName")
            }),
            makeRepoParamDict(expr.get(expr.deserializeRecord(snapshotConfig), "repoConfig"), false)
        )
    );
}


export const CreateSnapshot = WorkflowBuilder.create({
    k8sResourceName: "create-snapshot",
    parallelism: 100,
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)


    .addTemplate("runCreateSnapshot", t => t
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG_WITHOUT_SNAPSHOT_INFO>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("createSnapshotConfig", typeToken<z.infer<typeof ARGO_CREATE_SNAPSHOT_OPTIONS>>())
        .addRequiredInput("sourceK8sLabel", typeToken<string>())
        .addRequiredInput("snapshotK8sLabel", typeToken<string>())
        .addOptionalInput("taskK8sLabel", c => "snapshot")

        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addContainer(b => b
            .addImageInfo(b.inputs.imageMigrationConsoleLocation, b.inputs.imageMigrationConsolePullPolicy)
            .addCommand(["/root/createSnapshot/bin/CreateSnapshot"])
            .addVolumesFromRecord({
                'test-creds': {
                    configMap: {
                        name: expr.literal("localstack-test-creds"),
                        optional: true
                    },
                    mountPath: "/config/credentials",
                    readOnly: true
                }
            })
            .addEnvVar("AWS_SHARED_CREDENTIALS_FILE",
                expr.ternary(
                    expr.dig(expr.deserializeRecord(b.inputs.snapshotConfig), ["repoConfig", "useLocalStack"], false),
                    expr.literal("/config/credentials/configuration"),
                    expr.literal(""))
            )
            .addEnvVarsFromRecord(getSourceHttpAuthCreds(getHttpAuthSecretName(b.inputs.sourceConfig)))
            .addEnvVar("JDK_JAVA_OPTIONS",
                expr.dig(expr.deserializeRecord(b.inputs.createSnapshotConfig), ["jvmArgs"], "")
            )
            .addResources(DEFAULT_RESOURCES.JAVA_MIGRATION_CONSOLE_CLI)
            .addArgs([
                expr.literal("---INLINE-JSON"),
                expr.asString(expr.serialize(
                    makeParamsDict(b.inputs.sourceConfig, b.inputs.snapshotConfig, b.inputs.createSnapshotConfig)
                ))
            ])
            .addPodMetadata(({inputs}) => ({
                labels: {
                    'migrations.opensearch.org/source': inputs.sourceK8sLabel,
                    'migrations.opensearch.org/snapshot': inputs.snapshotK8sLabel,
                    'migrations.opensearch.org/task': inputs.taskK8sLabel
                }
            }))
        )
        .addRetryParameters(CONTAINER_TEMPLATE_RETRY_STRATEGY)
    )

    .addTemplate("applySnapshotDoneCronJob", t => t
        .addRequiredInput("configContents", typeToken<z.infer<typeof CONSOLE_SERVICES_CONFIG_FILE>>())
        .addRequiredInput("dataSnapshotName", typeToken<string>())
        .addRequiredInput("dataSnapshotUid", typeToken<string>())
        .addRequiredInput("snapshotName", typeToken<string>())
        .addRequiredInput("configChecksum", typeToken<string>())
        .addRequiredInput("sourceK8sLabel", typeToken<string>())
        .addRequiredInput("snapshotK8sLabel", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addContainer(b => b
            .addImageInfo(b.inputs.imageMigrationConsoleLocation, b.inputs.imageMigrationConsolePullPolicy)
            .addCommand(["/bin/bash", "-lc"])
            .addResources(DEFAULT_RESOURCES.SHELL_MIGRATION_CONSOLE_CLI)
            .addEnvVar("CRONJOB_NAME", getSnapshotDoneCronJobName(b.inputs.dataSnapshotName))
            .addEnvVarsFromRecord({
                SESSION_NAME: b.inputs.dataSnapshotName,
                ...workflowIdentityEnvVars(),
                DATASNAPSHOT_NAME: b.inputs.dataSnapshotName,
                DATASNAPSHOT_UID: b.inputs.dataSnapshotUid,
                SNAPSHOT_NAME: b.inputs.snapshotName,
                CONFIG_CHECKSUM: b.inputs.configChecksum,
                CONSOLE_IMAGE: b.inputs.imageMigrationConsoleLocation,
                CONSOLE_IMAGE_PULL_POLICY: b.inputs.imageMigrationConsolePullPolicy,
                SOURCE_LABEL: b.inputs.sourceK8sLabel,
                SNAPSHOT_LABEL: b.inputs.snapshotK8sLabel,
                CONSOLE_CONFIG_BASE64: expr.toBase64(expr.asString(b.inputs.configContents)),
                WORKFLOW_SCRIPTS_ROOT: t.inputs.workflowParameters.workflowScriptsRoot,
                SNAPSHOT_MONITOR_WORKFLOW_UID_LABEL: expr.literal(SNAPSHOT_MONITOR_WORKFLOW_UID_LABEL),
                SNAPSHOT_MONITOR_SESSION_LABEL: expr.literal(SNAPSHOT_MONITOR_SESSION_LABEL)
            })
            .addArgs([expr.concat(
                expr.literal("exec "),
                workflowScriptPath(t.inputs.workflowParameters.workflowScriptsRoot, "applySnapshotMonitorCronJob.sh")
            )])
        )
        .addRetryParameters({
            limit: "5", retryPolicy: "Always",
            backoff: {duration: "2", factor: "2", cap: "30"}
        })
    )


    .addTemplate("snapshotWorkflow", t => t
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG_WITHOUT_SNAPSHOT_INFO>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("createSnapshotConfig", typeToken<z.infer<typeof ARGO_CREATE_SNAPSHOT_OPTIONS>>())
        .addRequiredInput("semaphoreConfigMapName", typeToken<string>())
        .addRequiredInput("semaphoreKey", typeToken<string>())
        .addRequiredInput("configChecksum", typeToken<string>())
        .addRequiredInput("dataSnapshotName", typeToken<string>())
        .addRequiredInput("dataSnapshotUid", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addSteps(b => b
            .addStep("createSnapshot", INTERNAL, "runCreateSnapshot", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    sourceK8sLabel: expr.jsonPathStrict(b.inputs.sourceConfig, "label"),
                    snapshotK8sLabel: expr.jsonPathStrict(b.inputs.snapshotConfig, "label")
                }))

            .addStep("getConsoleConfig", MigrationConsole, "getConsoleConfig", c =>
                c.register({
                    ...selectInputsForRegister(b, c)
                }))

            .addStep("applySnapshotDoneCronJob", INTERNAL, "applySnapshotDoneCronJob", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    configContents: c.steps.getConsoleConfig.outputs.configContents,
                    dataSnapshotName: b.inputs.dataSnapshotName,
                    dataSnapshotUid: b.inputs.dataSnapshotUid,
                    snapshotName: expr.jsonPathStrict(b.inputs.snapshotConfig, "snapshotName"),
                    configChecksum: b.inputs.configChecksum,
                    sourceK8sLabel: expr.jsonPathStrict(b.inputs.sourceConfig, "label"),
                    snapshotK8sLabel: expr.jsonPathStrict(b.inputs.snapshotConfig, "label")
                }))
            .addStep("waitForCompletion", ResourceManagement, "waitForDataSnapshot", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    resourceName: b.inputs.dataSnapshotName,
                    configChecksum: b.inputs.configChecksum,
                    checksumField: expr.literal("checksumForSnapshotMigration"),
                }))
        )
        .addSynchronization(c => ({
            semaphores: [{
                configMapKeyRef: {
                    name: c.inputs.semaphoreConfigMapName,
                    key: c.inputs.semaphoreKey
                }
            }]
        }))
        .addExpressionOutput("snapshotConfig", b => b.inputs.snapshotConfig)
    )


    .getFullScope();
