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
    TARGET_CLUSTER_CONFIG,
    SOURCE_CLUSTER_CONFIG,
} from "@opensearch-migrations/schemas";
import {z} from "zod";

import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";
import {makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";
import {configureAndSubmitScript, monitorScript} from "../resourceLoader";

function makeMigrationParams(
    sourceConfig: BaseExpression<Serialized<z.infer<typeof SOURCE_CLUSTER_CONFIG>>>,
    targetConfig: BaseExpression<Serialized<z.infer<typeof TARGET_CLUSTER_CONFIG>>>
) {
    return expr.makeDict({
        skipApprovals: true,
        sourceClusters: expr.makeDict({
            source1: expr.cast(expr.mergeDicts(
                expr.deserializeRecord(sourceConfig),
                expr.makeDict({
                    snapshotRepo: {
                        awsRegion: "us-east-2",
                        endpoint: "localstack://localstack.ma.svc.cluster.local:4566",
                        s3RepoPathUri: "s3://migrations-default-123456789012-dev-us-east-2"
                    }
                })
            )).to<z.infer<typeof SOURCE_CLUSTER_CONFIG>>()
        }),
        targetClusters: expr.makeDict({
            target1: expr.deserializeRecord(targetConfig)
        }),
        migrationConfigs: [
            {
                fromSource: "source1",
                toTarget: "target1",
                skipApprovals: false,
                snapshotExtractAndLoadConfigs: [
                    {
                        snapshotConfig: {
                            snapshotNameConfig: {
                                snapshotNamePrefix: "source1snapshot1"
                            }
                        },
                        createSnapshotConfig: {},
                        migrations: [
                            {
                                metadataMigrationConfig: {
                                    skipEvaluateApproval: true,
                                    skipMigrateApproval: true
                                },
                                documentBackfillConfig: {
                                    documentsPerBulkRequest: 1000,
                                    maxShardSizeBytes: 16000000,
                                    maxConnections: 4,
                                    resources: {
                                        requests: {
                                            cpu: "250m",
                                            memory: "1Gi",
                                            "ephemeral-storage": "5Gi"
                                        },
                                        limits: {
                                            cpu: "250m",
                                            memory: "1Gi",
                                            "ephemeral-storage": "5Gi"
                                        }
                                    }
                                }
                            }
                        ]
                    }
                ],
                replayerConfig: {
                    podReplicas: 0
                }
            }
        ]
    });
}

export const testMigrationWithWorkflowCli = WorkflowBuilder.create({
    k8sResourceName: "full-migration-with-workflow-cli",
    parallelism: 1,
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)

    .addTemplate("buildMigrationConfig", t => t
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof SOURCE_CLUSTER_CONFIG>>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof TARGET_CLUSTER_CONFIG>>())
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
            limit: "10",          // ~72 hours (864 * 5min max backoff)
            retryPolicy: "Always",
            backoff: {
                duration: "5",     // Start at 5 seconds
                factor: "2",       // Exponential backoff  
                cap: "300"         // Cap at 5 minutes
            }
        })
    )

    .addTemplate("main", t => t
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof SOURCE_CLUSTER_CONFIG>>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof TARGET_CLUSTER_CONFIG>>())

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
