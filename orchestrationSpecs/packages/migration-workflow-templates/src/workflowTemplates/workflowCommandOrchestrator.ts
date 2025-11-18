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
    sourceConfig: defineParam({ 
        expression: expr.cast(expr.literal("")).to<string>(),
        description: "Source cluster config as JSON object fragment (e.g. {\"endpoint\":\"...\"})"
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

const WORKFLOW_CONFIGURE_SCRIPT = `
set -e -x

# Save pod name to output path
echo $HOSTNAME > /tmp/podname

echo "Building migration configuration..."

# Create migration config JSON
cat > /tmp/migration_config.json << 'EOF'
{
  "source_cluster": {{SOURCE_CONFIG}},
  "target_cluster": {{TARGET_CONFIG}},
  "snapshot": {{SNAPSHOT_CONFIG}},
  "backfill": {
    "reindex_from_snapshot": true
  },
  "metadata_migration": {
    "index_allowlist": "*"
  }
}
EOF

echo "Migration config contents:"
cat /tmp/migration_config.json

echo "Configuring workflow..."
. /etc/profile.d/venv.sh
source /.venv/bin/activate

workflow configure --config-file /tmp/migration_config.json

echo "Workflow configured successfully"
`;

const WORKFLOW_SUBMIT_SCRIPT = `
set -e -x

echo "Submitting workflow..."

. /etc/profile.d/venv.sh
source /.venv/bin/activate

# Submit workflow and capture the workflow name
WORKFLOW_OUTPUT=$(workflow submit 2>&1)
echo "Workflow submit output: $WORKFLOW_OUTPUT"

# Extract workflow name from output (format: "Workflow 'migration-xyz' submitted")
WORKFLOW_NAME=$(echo "$WORKFLOW_OUTPUT" | grep -o "Workflow '[^']*'" | sed "s/Workflow '\\([^']*\\)'/\\1/" || echo "")

if [ -z "$WORKFLOW_NAME" ]; then
    echo "ERROR: Could not extract workflow name from output"
    exit 1
fi

echo "Workflow submitted successfully: $WORKFLOW_NAME"
echo "$WORKFLOW_NAME" > /tmp/workflowName

# Also save to Argo output
mkdir -p /tmp/outputs
echo "$WORKFLOW_NAME" > /tmp/outputs/workflowName
`;

const WORKFLOW_MONITOR_SCRIPT = `
set -e -x

WORKFLOW_NAME="{{WORKFLOW_NAME}}"
echo "Monitoring workflow: $WORKFLOW_NAME"

. /etc/profile.d/venv.sh
source /.venv/bin/activate

# Poll workflow status with timeout (30 minutes max)
MAX_ITERATIONS=360  # 30 minutes at 5-second intervals
ITERATION=0

while [ $ITERATION -lt $MAX_ITERATIONS ]; do
    echo "Checking workflow status (iteration $((ITERATION + 1))/$MAX_ITERATIONS)..."
    
    # Get workflow status
    STATUS_OUTPUT=$(workflow status --name "$WORKFLOW_NAME" 2>&1 || echo "ERROR")
    
    if echo "$STATUS_OUTPUT" | grep -q "ERROR"; then
        echo "Error getting workflow status: $STATUS_OUTPUT"
        sleep 5
        ITERATION=$((ITERATION + 1))
        continue
    fi
    
    echo "Status output: $STATUS_OUTPUT"
    
    # Check if workflow is completed (succeeded or failed)
    if echo "$STATUS_OUTPUT" | grep -q "Succeeded\\|Failed"; then
        if echo "$STATUS_OUTPUT" | grep -q "Succeeded"; then
            echo "✅ Workflow completed successfully!"
            exit 0
        else
            echo "❌ Workflow failed!"
            exit 1
        fi
    fi
    
    # Check if workflow needs approval (suspended)
    if echo "$STATUS_OUTPUT" | grep -q "Suspended\\|Waiting"; then
        echo "Workflow is suspended, attempting to approve..."
        APPROVE_OUTPUT=$(workflow approve --name "$WORKFLOW_NAME" 2>&1)
        echo "Approve output: $APPROVE_OUTPUT"
        
        if echo "$APPROVE_OUTPUT" | grep -q "approved\\|Approved"; then
            echo "Workflow approved, continuing monitoring..."
        else
            echo "Warning: Approval may have failed: $APPROVE_OUTPUT"
        fi
    fi
    
    # Check if workflow is still running
    if echo "$STATUS_OUTPUT" | grep -q "Running\\|Pending"; then
        echo "Workflow is still running, waiting..."
    fi
    
    sleep 5
    ITERATION=$((ITERATION + 1))
done

echo "❌ Timeout: Workflow did not complete within 30 minutes"
exit 1
`;

export const WorkflowCommandOrchestrator = WorkflowBuilder.create({
    k8sResourceName: "workflow-command-orchestrator",
    parallelism: 1,
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)

    .addTemplate("configureWorkflow", t => t
        .addInputsFromRecord(configComponentParameters)
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addContainer(cb => cb
            .addImageInfo(cb.inputs.imageMigrationConsoleLocation, cb.inputs.imageMigrationConsolePullPolicy)
            .addCommand(["/bin/sh", "-c"])
            .addResources(DEFAULT_RESOURCES.MIGRATION_CONSOLE_CLI)
            .addArgs([expr.fillTemplate(WORKFLOW_CONFIGURE_SCRIPT, {
                "SOURCE_CONFIG": cb.inputs.sourceConfig,
                "TARGET_CONFIG": cb.inputs.targetConfig,
                "SNAPSHOT_CONFIG": cb.inputs.snapshotConfig
            })])
        )
    )

    .addTemplate("submitWorkflow", t => t
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addContainer(cb => cb
            .addImageInfo(cb.inputs.imageMigrationConsoleLocation, cb.inputs.imageMigrationConsolePullPolicy)
            .addCommand(["/bin/sh", "-c"])
            .addResources(DEFAULT_RESOURCES.MIGRATION_CONSOLE_CLI)
            .addArgs([WORKFLOW_SUBMIT_SCRIPT])
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
            .addArgs([expr.fillTemplate(WORKFLOW_MONITOR_SCRIPT, {
                "WORKFLOW_NAME": cb.inputs.workflowName
            })])
        )
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
            // Step 1: Configure workflow
            .addStep("configureWorkflow", INTERNAL, "configureWorkflow", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    sourceConfig: b.inputs.sourceClusterConfigJson,
                    targetConfig: b.inputs.targetClusterConfigJson,
                    snapshotConfig: b.inputs.snapshotConfigJson
                })
            )

            // Step 2: Submit workflow
            .addStep("submitWorkflow", INTERNAL, "submitWorkflow", c =>
                c.register({
                    ...selectInputsForRegister(b, c)
                })
            )

            // Step 3: Monitor workflow to completion (using actual workflow name from submit step)
            .addStep("monitorWorkflow", INTERNAL, "monitorWorkflow", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    workflowName: c.steps.submitWorkflow.outputs.workflowName
                })
            )
        )
    )

    .setEntrypoint("main")
    .getFullScope();
