import {z} from "zod";
import {
    AllowLiteralOrExpression,
    BaseExpression,
    defineParam,
    expr,
    INTERNAL,
    selectInputsForRegister,
    typeToken,
    WorkflowBuilder
} from "@opensearch-migrations/argo-workflow-builders";
import {
    CLUSTER_CONFIG,
    COMPLETE_SNAPSHOT_CONFIG,
    DEFAULT_RESOURCES,
    TARGET_CLUSTER_CONFIG
} from "@opensearch-migrations/schemas";

import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";
import {makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";

const configComponentParameters = {
    sourceEndpoint: defineParam({ 
        expression: expr.cast(expr.literal("")).to<string>(),
        description: "Source cluster endpoint URL"
    }),
    sourceAllowInsecure: defineParam({ 
        expression: expr.cast(expr.literal("true")).to<string>(),
        description: "Allow insecure connections to source cluster"
    }),
    sourceVersion: defineParam({ 
        expression: expr.cast(expr.literal("")).to<string>(),
        description: "Source cluster version (e.g. ES_5.6, OS_2.19)"
    }),
    targetConfig: defineParam({ 
        expression: expr.cast(expr.literal("")).to<string>(),
        description: "Target cluster config as JSON object fragment"
    }),
    snapshotConfig: defineParam({ 
        expression: expr.cast(expr.literal("")).to<string>(),
        description: "Snapshot config as JSON object fragment"
    })
};

const WORKFLOW_CONFIGURE_AND_SUBMIT_SCRIPT = `
set -e -x

echo "Building and submitting migration workflow..."

            # Create migration config JSON
            cat > /tmp/migration_config.json << 'EOF'
            {
              "sourceClusters": {
                "source": {
                  "endpoint": "{{inputs.parameters.sourceEndpoint}}",
                  "allowInsecure": {{inputs.parameters.sourceAllowInsecure}},
                  "version": "{{inputs.parameters.sourceVersion}}",
                  "snapshotRepo": {{inputs.parameters.snapshotConfig}}
                }
              },
              "targetClusters": {
                "target": {{inputs.parameters.targetConfig}}
              },
              "migrationConfigs": [
                {
                  "fromSource": "source",
                  "toTarget": "target",
                  "snapshotExtractAndLoadConfigs": [
                    {
                      "createSnapshotConfig": {},
                      "snapshotConfig": {
                        "snapshotNameConfig": {
                          "snapshotNamePrefix": "rfs"
                        }
                      },
                      "migrations": [
                        {
                          "metadataMigrationConfig": {
                            "indexAllowlist": ["*"]
                          },
                          "documentBackfillConfig": {
                            "indexAllowlist": ["*"]
                          }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
            EOF

echo "Migration config contents:"
cat /tmp/migration_config.json

. /etc/profile.d/venv.sh
source /.venv/bin/activate

# Clear and load configuration
echo "Clearing existing workflow configuration..."
workflow configure clear --confirm

echo "Loading configuration from JSON..."
cat /tmp/migration_config.json | workflow configure edit --stdin

# Submit workflow and capture the workflow name
echo "Submitting workflow..."
WORKFLOW_OUTPUT=$(workflow submit 2>&1)
echo "Workflow submit output: $WORKFLOW_OUTPUT"

# Extract workflow name (format: "  Name: workflow-name")
WORKFLOW_NAME=$(echo "$WORKFLOW_OUTPUT" | grep "Name:" | awk '{print $2}' | tr -d '\n')

if [ -z "$WORKFLOW_NAME" ]; then
    echo "ERROR: Could not extract workflow name from output"
    echo "Full output was: $WORKFLOW_OUTPUT"
    exit 1
fi

echo "Workflow submitted successfully: $WORKFLOW_NAME"
mkdir -p /tmp/outputs
echo "$WORKFLOW_NAME" > /tmp/outputs/workflowName
`;

const WORKFLOW_MONITOR_SCRIPT = `
set -e -x

WORKFLOW_NAME="{{WORKFLOW_NAME}}"
echo "Checking workflow status: $WORKFLOW_NAME"

. /etc/profile.d/venv.sh
source /.venv/bin/activate

# Simple status check - let Argo handle retries and persistence
STATUS_OUTPUT=$(workflow status --name "$WORKFLOW_NAME" 2>&1)
echo "Status: $STATUS_OUTPUT"

# Check if workflow is completed successfully
if echo "$STATUS_OUTPUT" | grep -q "Succeeded"; then
    echo "✅ Workflow completed successfully"
    exit 0
fi

# Check if workflow failed permanently 
if echo "$STATUS_OUTPUT" | grep -q "Failed"; then
    echo "❌ Workflow failed permanently"
    exit 1
fi

# Handle suspended workflow (needs approval)
if echo "$STATUS_OUTPUT" | grep -q "Suspended\\|Waiting"; then
    echo "Workflow is suspended, attempting to approve..."
    APPROVE_OUTPUT=$(workflow approve --name "$WORKFLOW_NAME" 2>&1)
    echo "Approve output: $APPROVE_OUTPUT"
    
    if echo "$APPROVE_OUTPUT" | grep -q "approved\\|Approved"; then
        echo "Workflow approved, will check again..."
    else
        echo "Warning: Approval may have failed: $APPROVE_OUTPUT"
    fi
    exit 1  # Exit 1 to retry after approval
fi

# Workflow is still running - exit 1 to trigger retry
if echo "$STATUS_OUTPUT" | grep -q "Running\\|Pending"; then
    echo "⏳ Workflow still running, will retry..."
    exit 1
fi

# Unknown status - retry
echo "Unknown workflow status, will retry..."
exit 1
`;

export const WorkflowCommandOrchestrator = WorkflowBuilder.create({
    k8sResourceName: "workflow-command-orchestrator",
    parallelism: 1,
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)

    .addTemplate("configureAndSubmitWorkflow", t => t
        .addInputsFromRecord(configComponentParameters)
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addContainer(cb => cb
            .addImageInfo(cb.inputs.imageMigrationConsoleLocation, cb.inputs.imageMigrationConsolePullPolicy)
            .addCommand(["/bin/sh", "-c"])
            .addResources(DEFAULT_RESOURCES.MIGRATION_CONSOLE_CLI)
            .addArgs([WORKFLOW_CONFIGURE_AND_SUBMIT_SCRIPT])
            .addPathOutput("workflowName", "/tmp/outputs/workflowName", typeToken<string>(), 
                "Name of the submitted workflow")
        )
    )

    .addTemplate("monitorWorkflow", t => t
        .addRequiredInput("workflowName", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addContainer(cb => cb
            .addImageInfo(cb.inputs.imageMigrationConsoleLocation, cb.inputs.imageMigrationConsolePullPolicy)
            .addCommand(["/bin/sh", "-c"])
            .addResources(DEFAULT_RESOURCES.MIGRATION_CONSOLE_CLI)
            .addArgs([WORKFLOW_MONITOR_SCRIPT.replace("{{WORKFLOW_NAME}}", "{{inputs.parameters.workflowName}}")])
        )
        .addRetryParameters({
            limit: "864",          // ~72 hours (864 * 5min max backoff)
            retryPolicy: "Always",
            backoff: {
                duration: "5",     // Start at 5 seconds
                factor: "2",       // Exponential backoff  
                cap: "300"         // Cap at 5 minutes
            }
        })
    )

    .addTemplate("main", t => t
        .addRequiredInput("sourceClusterConfigJson", typeToken<string>(), 
            "Source cluster configuration as JSON object fragment (e.g. {\"endpoint\":\"...\"})")
        .addRequiredInput("targetClusterConfigJson", typeToken<string>(), 
            "Target cluster configuration as JSON object fragment")
        .addRequiredInput("snapshotConfigJson", typeToken<string>(), 
            "Snapshot configuration as JSON object fragment")
        
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addSteps(b => b
            // Step 1: Configure and submit workflow (combined)
            .addStep("configureAndSubmitWorkflow", INTERNAL, "configureAndSubmitWorkflow", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    sourceEndpoint: "{{=jsonpath(inputs.parameters.sourceClusterConfigJson, '$.endpoint')}}",
                    sourceAllowInsecure: "{{=jsonpath(inputs.parameters.sourceClusterConfigJson, '$.allowInsecure')}}",
                    sourceVersion: "{{=jsonpath(inputs.parameters.sourceClusterConfigJson, '$.version')}}",
                    targetConfig: b.inputs.targetClusterConfigJson,
                    snapshotConfig: b.inputs.snapshotConfigJson
                })
            )

            // Step 2: Monitor workflow to completion (using actual workflow name from combined step)
            .addStep("monitorWorkflow", INTERNAL, "monitorWorkflow", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    workflowName: c.steps.configureAndSubmitWorkflow.outputs.workflowName
                })
            )
        )
    )

    .setEntrypoint("main")
    .getFullScope();
