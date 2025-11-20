
import {z} from "zod";
import {
    CLUSTER_CONFIG,
    COMPLETE_SNAPSHOT_CONFIG,
    CONSOLE_SERVICES_CONFIG_FILE,
    CREATE_SNAPSHOT_OPTIONS,
    DEFAULT_RESOURCES,
    METADATA_OPTIONS,
    NAMED_SOURCE_CLUSTER_CONFIG,
    NAMED_TARGET_CLUSTER_CONFIG,
    S3_REPO_CONFIG,
    TARGET_CLUSTER_CONFIG
} from "@opensearch-migrations/schemas";
import {MigrationConsole} from "./migrationConsole";
import {
    BaseExpression,
    expr,
    INTERNAL,
    selectInputsForRegister, Serialized,
    typeToken,
    WorkflowBuilder
} from "@opensearch-migrations/argo-workflow-builders";
import {makeRepoParamDict} from "./metadataMigration";

import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";
import {makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";
import {extractSourceKeysToExpressionMap, makeClusterParamDict} from "./commonUtils/clusterSettingManipulators";
import {getHttpAuthSecretName} from "./commonUtils/clusterSettingManipulators";
import {getSourceHttpAuthCreds, getTargetHttpAuthCreds} from "./commonUtils/basicCredsGetters";

export function makeSourceParamDict(sourceConfig: BaseExpression<Serialized<z.infer<typeof CLUSTER_CONFIG>>>) {
    return makeClusterParamDict("source", sourceConfig);
}

function makeParamsDict(
    sourceConfig: BaseExpression<Serialized<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG>>>,
    snapshotConfig: BaseExpression<Serialized<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>>,
    options: BaseExpression<Serialized<z.infer<typeof CREATE_SNAPSHOT_OPTIONS>>>
) {
    return expr.mergeDicts(
        expr.mergeDicts(
            makeSourceParamDict(sourceConfig),
            expr.omit(expr.deserializeRecord(options), "loggingConfigurationOverrideConfigMap")
        ),
        expr.mergeDicts(
            expr.makeDict({
                "snapshotName": expr.get(expr.deserializeRecord(snapshotConfig), "snapshotName"),
                "snapshotRepoName": expr.dig(expr.deserializeRecord(snapshotConfig), ["repoConfig", "repoName"],
                    S3_REPO_CONFIG.shape.repoName.unwrap().parse(undefined))
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
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("createSnapshotConfig", typeToken<z.infer<typeof CREATE_SNAPSHOT_OPTIONS>>())

        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addContainer(b=>b
            .addImageInfo(b.inputs.imageMigrationConsoleLocation, b.inputs.imageMigrationConsolePullPolicy)
            .addCommand(["/root/createSnapshot/bin/CreateSnapshot"])
            .addEnvVarsFromRecord(getSourceHttpAuthCreds(getHttpAuthSecretName(b.inputs.sourceConfig)))
            .addResources(DEFAULT_RESOURCES.MIGRATION_CONSOLE_CLI)
            .addArgs([
                expr.literal("---INLINE-JSON"),
                expr.asString(expr.serialize(
                    makeParamsDict(b.inputs.sourceConfig, b.inputs.snapshotConfig, b.inputs.createSnapshotConfig)
                ))
            ])
        )

    )

    .addTemplate("checkSnapshotStatus", t => t
        .addRequiredInput("configContents", typeToken<z.infer<typeof CONSOLE_SERVICES_CONFIG_FILE>>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addSteps(b => b
            .addStep("checkSnapshotCompletion", MigrationConsole, "runMigrationCommand", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    command: "set -e && [ \"$(console --config-file=/config/migration_services.yaml snapshot status)\" = \"SUCCESS\" ] && exit 0 || exit 1"
                }))
        )
        .addRetryParameters({
            limit: "200", retryPolicy: "Always",
            backoff: {duration: "5", factor: "2", cap: "300"}
        })
    )


    .addTemplate("snapshotWorkflow", t => t
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("createSnapshotConfig", typeToken<z.infer<typeof CREATE_SNAPSHOT_OPTIONS>>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addSteps(b => b

            .addStep("createSnapshot", INTERNAL, "runCreateSnapshot", c =>
                c.register(selectInputsForRegister(b, c)))

            .addStep("getConsoleConfig", MigrationConsole, "getConsoleConfig", c =>
                c.register({
                    ...selectInputsForRegister(b, c)
                }))

            .addStep("checkSnapshotStatus", INTERNAL, "checkSnapshotStatus", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    configContents: c.steps.getConsoleConfig.outputs.configContents
                }))
        )
        .addExpressionOutput("snapshotConfig", b => b.inputs.snapshotConfig)
    )


    .getFullScope();
