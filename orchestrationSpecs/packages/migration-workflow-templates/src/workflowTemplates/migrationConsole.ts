
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

const KafkaServicesConfig = z.object({
    broker_endpoints: z.string(),
    standard: z.string()
})

export const configComponentParameters = {
    kafkaInfo: defineParam({ expression: expr.cast(expr.literal("")).to<z.infer<typeof KafkaServicesConfig>>(),
        description: "Snapshot configuration information (JSON)"}),
    sourceConfig: defineParam({ expression: expr.cast(expr.literal("")).to<z.infer<typeof CLUSTER_CONFIG>>(),
        description: "Source cluster configuration (JSON)"}),
    targetConfig: defineParam({ expression: expr.cast(expr.literal("")).to<z.infer<typeof TARGET_CLUSTER_CONFIG>>(),
        description: "Target cluster configuration (JSON)"}),
    snapshotConfig: defineParam({ expression: expr.cast(expr.literal("")).to<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>(),
        description: "Snapshot configuration information (JSON)"})
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
jq '
# Normalize auth config for cluster objects (source_cluster, target_cluster)
def normalizeClusterAuthConfig:
  if has("authConfig") then
    (if (.authConfig | has("basic")) then
      .basic_auth = (.authConfig.basic |
        if has("secretName") then
          .k8s_secret_name = .secretName | del(.secretName)
        else
          .
        end)
    elif (.authConfig | has("sigv4")) then
      .sigv4 = .authConfig.sigv4
    elif (.authConfig | has("mtls")) then
      .mtls_auth = .authConfig.mtls
    else
      .no_auth = {}
    end)
    | del(.authConfig)
  else
    .no_auth = {}
  end
  | del(.name, .proxy, .snapshotRepo);

def normalizeAllowInsecure:
  if has("allowInsecure") then
    .allow_insecure = .allowInsecure | del(.allowInsecure)
  else
    .
  end;

def normalizeSnapshotName:
  if has("snapshotName") then
    .snapshot_name = .snapshotName | del(.snapshotName)
  else
    .
  end;

# Normalize S3 config inside snapshot
def normalizeS3Config:
  (if has("s3RepoPathUri") then
    .repo_uri = .s3RepoPathUri | del(.s3RepoPathUri)
  else
    .
  end)
  | del(.repoName, .useLocalStack);

def normalizeRepoConfig:
  if has("repoConfig") then
    .s3 = (.repoConfig | 
      if has("awsRegion") then
        .aws_region = .awsRegion | del(.awsRegion)
      else
        .
      end
      | normalizeS3Config)
    | del(.repoConfig)
  elif has("s3") then
    .s3 |= normalizeS3Config
  else
    .
  end;

# Normalize cluster config (only for source_cluster and target_cluster)
def normalizeCluster:
  normalizeClusterAuthConfig | normalizeAllowInsecure;

# Normalize snapshot config
def normalizeSnapshot:
  normalizeSnapshotName | normalizeRepoConfig;

# Apply transformations to specific top-level keys
(if has("source_cluster") then .source_cluster |= normalizeCluster else . end)
| (if has("target_cluster") then .target_cluster |= normalizeCluster else . end)
| (if has("snapshot") then .snapshot |= normalizeSnapshot else . end)
' > /config/migration_services.yaml

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
                    makeOptionalDict("kafka", expr.asString(c.inputs.kafkaInfo), typeToken<z.infer<typeof KAFKA_SERVICES_CONFIG>>()),
                    makeOptionalDict("source_cluster", expr.asString(c.inputs.sourceConfig), typeToken<z.infer<typeof CLUSTER_CONFIG>>())
                ),
                expr.mergeDicts(
                    makeOptionalDict("target_cluster", expr.asString(c.inputs.targetConfig), typeToken<z.infer<typeof TARGET_CLUSTER_CONFIG>>()),
                    makeOptionalDict("snapshot", expr.asString(c.inputs.snapshotConfig), typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
                )
            ))
        )
    )


    .addTemplate("runMigrationCommand", t => t
        .addRequiredInput("command", typeToken<string>())
        .addRequiredInput("configContents", typeToken<z.infer<typeof CONSOLE_SERVICES_CONFIG_FILE>>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addContainer(c => c
            .addImageInfo(c.inputs.imageMigrationConsoleLocation, c.inputs.imageMigrationConsolePullPolicy)
            .addCommand(["/bin/sh", "-c"])
            .addEnvVarsFromRecord(getSourceHttpAuthCreds(
                expr.dig(expr.deserializeRecord(c.inputs.configContents), ["source_cluster","authConfig","basic","secretName"], "")))
            .addEnvVarsFromRecord(getTargetHttpAuthCreds(
                expr.dig(expr.deserializeRecord(c.inputs.configContents), ["target_cluster","authConfig","basic","secretName"], "")))
            .addResources(DEFAULT_RESOURCES.MIGRATION_CONSOLE_CLI)
            .addArgs([
                expr.fillTemplate(SCRIPT_ARGS_FILL_CONFIG_AND_RUN_TEMPLATE, {
                    "FILE_CONTENTS": expr.toBase64(expr.asString(c.inputs.configContents)),
                    "COMMAND": c.inputs.command
                })]
            )
        )
    )


    .addTemplate("runConsole", t => t
        .addRequiredInput("command", typeToken<string>())
        .addInputsFromRecord(configComponentParameters)
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addSteps(s => s
            .addStep("getConsoleConfig", INTERNAL, "getConsoleConfig", c =>
                c.register(selectInputsForRegister(s, c)))


            .addStep("runConsoleWithConfig", INTERNAL, "runMigrationCommand", c =>
                c.register({
                    ...selectInputsForRegister(s, c),
                    configContents: c.steps.getConsoleConfig.outputs.configContents
                }))
        )
    )



    .getFullScope();
