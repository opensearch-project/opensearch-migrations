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

import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";
import {makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";
import {configureAndSubmitScript, monitorScript} from "../testResourceLoader";

export const TestMigrationWithWorkflowCli = WorkflowBuilder.create({
    k8sResourceName: "full-migration-with-workflow-cli",
    parallelism: 1,
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)

    .addParams({
        // Max retries for monitoring workflow (exponential backoff: 2s start, factor 2, cap 15s)
        // Default 33 (~8 min with backoff), use 900 for longer migrations
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
            .addCommand(["/bin/bash", "-c"])
            .addResources(DEFAULT_RESOURCES.PYTHON_MIGRATION_CONSOLE_CLI)
            .addEnvVar("MIGRATION_CONFIG_BASE64", cb.inputs.migrationConfigBase64)
            .addArgs([configureAndSubmitScript])
        )
    )

    .addTemplate("monitorWorkflow", t => t
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addContainer(cb => cb
            .addImageInfo(cb.inputs.imageMigrationConsoleLocation, cb.inputs.imageMigrationConsolePullPolicy)
            .addCommand(["/bin/bash", "-c"])
            .addResources(DEFAULT_RESOURCES.PYTHON_MIGRATION_CONSOLE_CLI)
            .addArgs([monitorScript])
            // Monitor script exit codes:
            // - Exit 0: Workflow is in terminal state (Succeeded or Failed) - stop retrying
            // - Exit 1: Workflow still running - retry monitoring
            .addPathOutput(
                "monitorResult",
                "/tmp/outputs/monitorResult",
                typeToken<string>(),
                "Workflow status output from monitor script"
            )
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
            .addCommand(["/bin/bash", "-c"])
            .addResources(DEFAULT_RESOURCES.PYTHON_MIGRATION_CONSOLE_CLI)
            .addEnvVar("MONITOR_RESULT", cb.inputs.monitorResult)
            .addArgs([`
set -e
echo "Evaluating workflow result..."
echo "Monitor output: $MONITOR_RESULT"

# Check if the output contains "Phase: Succeeded"
if echo "$MONITOR_RESULT" | grep -q "Phase: Succeeded"; then
    echo "Migration workflow completed successfully"
    exit 0
else
    echo "Migration workflow did not succeed"
    exit 1
fi
            `])
        )
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
    )

    .addTemplate("main", t => t
        // Accept pre-built migration config as base64-encoded JSON
        .addRequiredInput("migrationConfigBase64", typeToken<string>())

        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

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

            // Step 4: Delete the migration workflow (always executes)
            .addStep("deleteMigrationWorkflow", INTERNAL, "deleteMigrationWorkflow")
        )
    )

    .setEntrypoint("main")
    .getFullScope();
