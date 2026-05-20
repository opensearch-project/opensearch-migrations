import {
    defineParam,
    expr,
    INTERNAL,
    selectInputsForRegister,
    typeToken,
    WorkflowBuilder
} from "@opensearch-migrations/argo-workflow-builders";
import {
    DEFAULT_RESOURCES,
} from "@opensearch-migrations/schemas";

import {CommonWorkflowParameters, workflowScriptPath} from "./commonUtils/workflowParameters";
import {makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";
import {K8S_RESOURCE_RETRY_STRATEGY, CONTAINER_TEMPLATE_RETRY_STRATEGY} from "./commonUtils/resourceRetryStrategy";

export const TestMigrationWithWorkflowCli = WorkflowBuilder.create({
    k8sResourceName: "full-migration-with-workflow-cli",
    parallelism: 1,
    serviceAccountName: "argo-test-workflow-executor"
})

    .addParams(CommonWorkflowParameters)

    .addParams({
        // Max retries for monitoring workflow with fixed 60s interval between attempts
        // Default 33 (~33 min at 60s intervals); increase (e.g. 900) for longer migrations
        "monitor-retry-limit": defineParam({expression: "33"})
    })

    .addTemplate("configureAndSubmitWorkflow", t => t
        // TODO: Remove base64 encoding to maintain strong typing throughout workflows
        // Currently using base64 as a workaround for passing complex JSON through Argo workflow parameters.
        // Consider using ConfigMaps or a dedicated typed parameter passing mechanism instead.
        .addRequiredInput("migrationConfigBase64", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addContainer(cb => cb
            .addImageInfo(cb.inputs.imageMigrationConsoleLocation, cb.inputs.imageMigrationConsolePullPolicy)
            .addCommand(["/bin/bash", "-lc"])
            .addResources(DEFAULT_RESOURCES.PYTHON_MIGRATION_CONSOLE_CLI)
            .addEnvVar("MIGRATION_CONFIG_BASE64", cb.inputs.migrationConfigBase64)
            .addArgs([expr.concat(
                expr.literal("exec "),
                workflowScriptPath(t.inputs.workflowParameters.workflowScriptsRoot, "configureAndSubmitWorkflow.sh")
            )])
        )
        .addRetryParameters(CONTAINER_TEMPLATE_RETRY_STRATEGY)
    )

    .addTemplate("monitorWorkflow", t => t
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addContainer(cb => cb
            .addImageInfo(cb.inputs.imageMigrationConsoleLocation, cb.inputs.imageMigrationConsolePullPolicy)
            .addCommand(["/bin/bash", "-lc"])
            .addResources(DEFAULT_RESOURCES.PYTHON_MIGRATION_CONSOLE_CLI)
            .addArgs([expr.concat(
                expr.literal("exec "),
                workflowScriptPath(t.inputs.workflowParameters.workflowScriptsRoot, "monitorWorkflow.sh")
            )])
            // Monitor script exit codes:
            // - Exit 0: Workflow is in terminal state (Succeeded or Failed) - stop retrying
            // - Exit 1: Workflow still running - retry monitoring
            .addPathOutput(
                "monitorResult",
                "/tmp/outputs/monitorResult",
                typeToken<string>(),
                "Workflow status output from monitor script"
            )
            .addArtifactOutput("monitorResult", "/tmp/outputs/monitorResult")
        )
        .addRetryParameters({
            limit: "{{workflow.parameters.monitor-retry-limit}}",
            retryPolicy: "Always",
            backoff: {
                duration: "60",    // Fixed 60 second interval
                factor: "1"        // No exponential increase
            }
        })
    )

    .addTemplate("evaluateWorkflowResult", t => t
        .addRequiredInput("monitorResult", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addContainer(cb => cb
            .addImageInfo(cb.inputs.imageMigrationConsoleLocation, cb.inputs.imageMigrationConsolePullPolicy)
            .addCommand(["/bin/bash", "-lc"])
            .addResources(DEFAULT_RESOURCES.PYTHON_MIGRATION_CONSOLE_CLI)
            .addEnvVar("MONITOR_RESULT", cb.inputs.monitorResult)
            .addArgs([expr.concat(
                expr.literal("exec "),
                workflowScriptPath(t.inputs.workflowParameters.workflowScriptsRoot, "evaluateWorkflowResult.sh")
            )])
        )
        .addRetryParameters(CONTAINER_TEMPLATE_RETRY_STRATEGY)
    )

    .addTemplate("deleteMigrationWorkflow", t => t
        .addResourceTask(b => b
            .setDefinition({
                action: "delete",
                flags: ["--ignore-not-found"],
                manifest: {
                    "apiVersion": "argoproj.io/v1alpha1",
                    "kind": "Workflow",
                    "metadata": {
                        "name": expr.literal("migration-workflow")
                    }
                }
            })
        )
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )

    .addTemplate("main", t => t
        // Accept pre-built migration config as base64-encoded JSON
        .addRequiredInput("migrationConfigBase64", typeToken<string>())

        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        // When true, skip deleting the inner migration-workflow (useful for local debugging)
        .addOptionalInput("keepMigrationWorkflow", () => false)

        .addSteps(b => b
            // Step 1: Configure and submit workflow
            .addStep("configureAndSubmitWorkflow", INTERNAL, "configureAndSubmitWorkflow", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    migrationConfigBase64: b.inputs.migrationConfigBase64,
                })
            )

            // Step 2: Monitor workflow to completion (with retry on exit code 1)
            .addStep("monitorWorkflow", INTERNAL, "monitorWorkflow", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                })
            )

            // Step 3: Evaluate workflow result (check for SUCCESS in output)
            .addStep("evaluateWorkflowResult", INTERNAL, "evaluateWorkflowResult", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    monitorResult: c.steps.monitorWorkflow.outputs.monitorResult,
                })
            )

            // Step 4: Delete the migration workflow (skipped when keepMigrationWorkflow=true)
            .addStep("deleteMigrationWorkflow", INTERNAL, "deleteMigrationWorkflow",
                {when: expr.not(b.inputs.keepMigrationWorkflow)}
            )
        )
    )

    .setEntrypoint("main")
    .getFullScope();
