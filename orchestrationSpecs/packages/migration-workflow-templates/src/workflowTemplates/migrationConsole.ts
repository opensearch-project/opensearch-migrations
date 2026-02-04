
import {z} from "zod";
import {
    AllowLiteralOrExpression,
    BaseExpression, defineParam,
    defineRequiredParam,
    expr,
    IMAGE_PULL_POLICY,
    INTERNAL,
    PlainObject,
    selectInputsForRegister,
    Serialized,
    TypeToken,
    typeToken,
    WorkflowBuilder
} from "@opensearch-migrations/argo-workflow-builders";
import {
    CLUSTER_CONFIG,
    COMPLETE_SNAPSHOT_CONFIG,
    CONSOLE_SERVICES_CONFIG_FILE,
    DEFAULT_RESOURCES,
    KAFKA_SERVICES_CONFIG,
    ResourceRequirementsType,
    TARGET_CLUSTER_CONFIG
} from "@opensearch-migrations/schemas";

import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";
import {makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";
import {getSourceHttpAuthCreds, getTargetHttpAuthCreds} from "./commonUtils/basicCredsGetters";

export const CONSOLE_KAFKA_SERVICES_CONFIG = z.object({
    broker_endpoints: z.string(),
    standard: z.string()
});

export const CONSOLE_BACKFILL_INFO = z.object({
    sessionName: z.string(),
    deploymentName: z.string()
});

export const configComponentParameters = {
    backfillSession: defineParam({ expression: expr.cast(expr.literal("")).to<z.infer<typeof CONSOLE_BACKFILL_INFO>>(),
        description: "The metadata about the deployment performing the RFS backfill"}),
    kafkaInfo: defineParam({ expression: expr.cast(expr.literal("")).to<z.infer<typeof CONSOLE_KAFKA_SERVICES_CONFIG>>(),
        description: "Snapshot configuration information (JSON)"}),
    sourceConfig: defineParam({ expression: expr.cast(expr.literal("")).to<z.infer<typeof CLUSTER_CONFIG>>(),
        description: "Source cluster configuration (JSON)"}),
    snapshotConfig: defineParam({ expression: expr.cast(expr.literal("")).to<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>(),
        description: "Snapshot configuration information (JSON)"}),
    targetConfig: defineParam({ expression: expr.cast(expr.literal("")).to<z.infer<typeof TARGET_CLUSTER_CONFIG>>(),
        description: "Target cluster configuration (JSON)"})
};

// TODO - once the migration console can load secrets from env variables,
//  we'll want to just drop everything about the secrets for http auth
const SCRIPT_ARGS_FILL_CONFIG_AND_RUN_TEMPLATE = `
set -e -x

# Save pod name to output path
echo $HOSTNAME > /tmp/podname

echo File contents...
 
base64 -d > /config/migration_services.yaml_ << EOF
{{FILE_CONTENTS}}
EOF

cat /config/migration_services.yaml_ |
jq -f workflowConfigToServicesConfig.jq > /config/migration_services.yaml

. /etc/profile.d/venv.sh
source /.venv/bin/activate

echo file dump
echo ---
export MIGRATION_USE_SERVICES_YAML_CONFIG=true
cat /config/migration_services.yaml
echo ---

{{COMMAND}}
`;

function makeOptionalDict<
    T extends PlainObject,
    SCHEMA extends PlainObject
>(label: string, v: BaseExpression<T>, tt: TypeToken<SCHEMA>) {
    return expr.ternary(expr.isEmpty(v), expr.literal({}),
        expr.makeDict({[label]: expr.stringToRecord(tt, expr.asString(v))}));
}

export const MigrationConsole = WorkflowBuilder.create({
    k8sResourceName: "migration-console",
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)


    .addTemplate("getConsoleConfig", b => b
        .addInputsFromRecord(configComponentParameters)
        .addSteps(s => s
            .addStepGroup(c => c))
        .addExpressionOutput("configContents", c =>
            expr.recordToString(expr.mergeDicts(
                    expr.mergeDicts(
                        expr.mergeDicts(
                            makeOptionalDict("kafka", expr.asString(c.inputs.kafkaInfo), typeToken<z.infer<typeof KAFKA_SERVICES_CONFIG>>()),
                            makeOptionalDict("source_cluster", expr.asString(c.inputs.sourceConfig), typeToken<z.infer<typeof CLUSTER_CONFIG>>())
                        ),
                        expr.mergeDicts(
                            makeOptionalDict("target_cluster", expr.asString(c.inputs.targetConfig), typeToken<z.infer<typeof TARGET_CLUSTER_CONFIG>>()),
                            makeOptionalDict("snapshot", expr.asString(c.inputs.snapshotConfig), typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
                        )
                    ),
                    expr.ternary(expr.isEmpty(c.inputs.backfillSession), expr.literal({}), expr.makeDict({
                        "backfill": expr.makeDict({
                            "reindex_from_snapshot": expr.makeDict({
                                "k8s": expr.makeDict({
                                    "namespace": expr.getWorkflowValue("namespace"),
                                    "deployment_name": expr.get(expr.deserializeRecord(c.inputs.backfillSession), "deploymentName")
                                }),
                                "backfill_session_name": expr.get(expr.deserializeRecord(c.inputs.backfillSession), "sessionName")
                            })
                        })}))
                )
            )
        )
    )


    .addTemplate("runMigrationCommandForStatus", t => t
        .addRequiredInput("command", typeToken<string>())
        .addRequiredInput("configContents", typeToken<z.infer<typeof CONSOLE_SERVICES_CONFIG_FILE>>())
        .addOptionalInput("sourceK8sLabel", c => "")
        .addOptionalInput("targetK8sLabel", c => "")
        .addOptionalInput("snapshotK8sLabel", c => "")
        .addOptionalInput("fromSnapshotMigrationK8sLabel", c => "")
        .addOptionalInput("taskK8sLabel", c => "")
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addContainer(c => c
            .addImageInfo(c.inputs.imageMigrationConsoleLocation, c.inputs.imageMigrationConsolePullPolicy)
            .addCommand(["/bin/sh", "-c"])
            .addEnvVarsFromRecord(getSourceHttpAuthCreds(
                expr.dig(expr.deserializeRecord(c.inputs.configContents), ["source_cluster","authConfig","basic","secretName"], "")))
            .addEnvVarsFromRecord(getTargetHttpAuthCreds(
                expr.dig(expr.deserializeRecord(c.inputs.configContents), ["target_cluster","authConfig","basic","secretName"], "")))
            .addResources(DEFAULT_RESOURCES.JAVA_MIGRATION_CONSOLE_CLI)
            .addArgs([
                expr.fillTemplate(SCRIPT_ARGS_FILL_CONFIG_AND_RUN_TEMPLATE, {
                    "FILE_CONTENTS": expr.toBase64(expr.asString(c.inputs.configContents)),
                    "COMMAND": c.inputs.command
                })]
            )
            .addPodMetadata(({ inputs }) => ({
                labels: {
                    'migrations.opensearch.org/source': inputs.sourceK8sLabel,
                    'migrations.opensearch.org/target': inputs.targetK8sLabel,
                    'migrations.opensearch.org/snapshot': inputs.snapshotK8sLabel,
                    'migrations.opensearch.org/from-snapshot-migration': inputs.fromSnapshotMigrationK8sLabel,
                    'migrations.opensearch.org/task': inputs.taskK8sLabel
                }
            }))
        )
        .addPathOutput("statusOutput", "/tmp/status-output.txt", typeToken<string>())
        .addPathOutput("overriddenPhase", "/tmp/phase-output.txt", typeToken<string>())
    )


    .addTemplate("runConsole", t => t
        .addRequiredInput("command", typeToken<string>())
        .addInputsFromRecord(configComponentParameters)
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addSteps(s => s
            .addStep("getConsoleConfig", INTERNAL, "getConsoleConfig", c =>
                c.register(selectInputsForRegister(s, c)))


            .addStep("runConsoleWithConfig", INTERNAL, "runMigrationCommandForStatus", c =>
                c.register({
                    ...selectInputsForRegister(s, c),
                    configContents: c.steps.getConsoleConfig.outputs.configContents
                }))
        )
    )



    .getFullScope();
