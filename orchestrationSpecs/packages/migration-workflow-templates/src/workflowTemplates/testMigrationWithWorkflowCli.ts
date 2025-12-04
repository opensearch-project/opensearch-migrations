import {
    BaseExpression,
    expr,
    INTERNAL,
    selectInputsForRegister,
    Serialized,
    typeToken,
    WorkflowBuilder
} from "@opensearch-migrations/argo-workflow-builders";
import {
    DEFAULT_RESOURCES,
    NAMED_SOURCE_CLUSTER_CONFIG,
    NAMED_TARGET_CLUSTER_CONFIG,
} from "@opensearch-migrations/schemas";
import {z} from "zod";

import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";
import {makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";
import {configureAndSubmitScript, monitorScript} from "../resourceLoader";

function makeMigrationParams(
    sourceConfig: BaseExpression<Serialized<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG>>>,
    targetConfig: BaseExpression<Serialized<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>>
) {
    return expr.makeDict({
        skipApprovals: true,
        sourceClusters: expr.makeDict({
            source1: expr.deserializeRecord(sourceConfig)
        }),
        targetClusters: expr.makeDict({
            target1: expr.deserializeRecord(targetConfig)
        }),
        migrationConfigs: [
            {
                fromSource: "source1",
                toTarget: "target1",
                snapshotExtractAndLoadConfigs: [
                    {
                        snapshotConfig: {
                            snapshotNameConfig: {
                                snapshotNamePrefix: "source1snapshot1"
                            }
                        },
                        migrations: [
                            {
                                metadataMigrationConfig: {},
                                documentBackfillConfig: {}
                            }
                        ]
                    }
                ]
            }
        ]
    });
}

export const testMigrationWithWorkflowCli = WorkflowBuilder.create({
    k8sResourceName: "full-migration-with-clusters",
    parallelism: 1,
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)

    .addTemplate("buildMigrationConfig", t => t
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG>>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("migrationConfigBase64", c =>
            expr.toBase64(expr.asString(expr.serialize(
                makeMigrationParams(c.inputs.sourceConfig, c.inputs.targetConfig)
            )))
        )
    )

    .addTemplate("configureAndSubmitWorkflow", t => t
        .addRequiredInput("migrationConfigBase64", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addContainer(cb => cb
            .addImageInfo(cb.inputs.imageMigrationConsoleLocation, cb.inputs.imageMigrationConsolePullPolicy)
            .addCommand(["/bin/bash", "-c"])
            .addResources(DEFAULT_RESOURCES.MIGRATION_CONSOLE_CLI)
            .addEnvVar("MIGRATION_CONFIG_BASE64", cb.inputs.migrationConfigBase64)
            .addArgs([configureAndSubmitScript])
        )
    )

    .addTemplate("monitorWorkflow", t => t
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addContainer(cb => cb
            .addImageInfo(cb.inputs.imageMigrationConsoleLocation, cb.inputs.imageMigrationConsolePullPolicy)
            .addCommand(["/bin/bash", "-c"])
            .addResources(DEFAULT_RESOURCES.MIGRATION_CONSOLE_CLI)
            .addArgs([monitorScript])
            .addPathOutput(
                "monitorResult",
                "/tmp/outputs/monitorResult",
                typeToken<string>(),
                "Result of monitoring (SUCCEEDED, FAILED, RETRY, ERROR)"
            )
        )
        .addRetryParameters({
            limit: "3",          // ~72 hours (864 * 5min max backoff)
            retryPolicy: "Always",
            backoff: {
                duration: "5",     // Start at 5 seconds
                factor: "2",       // Exponential backoff  
                cap: "300"         // Cap at 5 minutes
            }
        })
    )

    .addTemplate("main", t => t
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG>>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())

        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addSteps(b => b
            // Step 0: Build migration config and encode as base64
            .addStep("buildMigrationConfig", INTERNAL, "buildMigrationConfig", c =>
                c.register({
                    sourceConfig: b.inputs.sourceConfig,
                    targetConfig: b.inputs.targetConfig,
                })
            )

            // Step 1: Configure and submit workflow (combined)
            .addStep("configureAndSubmitWorkflow", INTERNAL, "configureAndSubmitWorkflow", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    migrationConfigBase64: c.steps.buildMigrationConfig.outputs.migrationConfigBase64,
                })
            )

            // Step 2: Monitor workflow to completion
            .addStep("monitorWorkflow", INTERNAL, "monitorWorkflow", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                })
            )
        )
    )

    .setEntrypoint("main")
    .getFullScope();
