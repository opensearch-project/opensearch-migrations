
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

const configComponentParameters = {
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
jq 'def normalizeAuthConfig:
  if has("authConfig") then
    if (.authConfig | has("basic")) then
      .basic_auth = (.authConfig.basic | 
        if has("secretName") then
          del(.secretName)
        else
          .
        end)
    elif (.authConfig | has("sigv4")) then
      .sigv4 = .authConfig.sigv4
    elif (.authConfig | has("mtls")) then
      .mtls_auth = .authConfig.mtls
    else
      .
    end
    | del(.authConfig, .name, .proxy, .snapshotRepo)
  else
    .
  end;

def normalizeAllowInsecure:
  if has("allowInsecure") then
    .allow_insecure = .allowInsecure | del(.allowInsecure)
  else
    .
  end;

def normalizeRepoPath:
  if has("s3RepoPathUri") then
    .repo_uri = .s3RepoPathUri | del(.s3RepoPathUri) | del(.repoName)
  else
    .
  end;

def normalizeSnapshotName:
  if has("snapshotName") then
    .snapshot_name = .snapshotName | del(.snapshotName)
  else
    .
  end;

def normalizeRepoConfig:
  if has("repoConfig") then
    .s3 = (.repoConfig | 
      if has("awsRegion") then
        .aws_region = .awsRegion | del(.awsRegion)
      else
        .
      end)
    | del(.repoConfig)
  else
    .
  end;

def removeUseLocalStack:
  del(.useLocalStack);

# Apply recursively to catch nested objects
def recurseNormalize:
  (normalizeAuthConfig
   | normalizeAllowInsecure
   | normalizeRepoPath
   | normalizeSnapshotName
   | normalizeRepoConfig
   | removeUseLocalStack)
  | with_entries(.value |= (if type=="object" then (.|recurseNormalize) else . end));

. | recurseNormalize
' > /config/migration_services.yaml

. /etc/profile.d/venv.sh
source /.venv/bin/activate

echo file dump
echo ---
cat /config/migration_services.yaml
echo ---

{{COMMAND}}
`;

function getConsoleDeploymentResource(
    name: AllowLiteralOrExpression<string>,
    migrationConsoleImage: AllowLiteralOrExpression<string>,
    migrationConsolePullPolicy: AllowLiteralOrExpression<IMAGE_PULL_POLICY>,
    base64ConfigContents: AllowLiteralOrExpression<string>,
    command: AllowLiteralOrExpression<string>,
    resources: AllowLiteralOrExpression<ResourceRequirementsType>
) {
    return {
        "apiVersion": "apps/v1",
        "kind": "Deployment",
        "metadata": {
            "name": name
        },
        "spec": {
            "replicas": 1,
            "selector": {
                "matchLabels": {
                    "app": "user-environment"
                }
            },
            "template": {
                "metadata": {
                    "labels": {
                        "app": "user-environment"
                    }
                },
                "spec": {
                    "containers": [
                        {
                            "name": "main",
                            "image": migrationConsoleImage,
                            "imagePullPolicy": migrationConsolePullPolicy,
                            resources,
                            "command": [
                                "/bin/sh",
                                "-c",
                                "set -e -x\n\nbase64 -d > /config/migration_services.yaml << EOF\n" +
                                "" +
                                base64ConfigContents +
                                "EOF\n" +
                                "" +
                                ". /etc/profile.d/venv.sh\n" +
                                "source /.venv/bin/activate\n" +
                                "" +
                                "echo file dump\necho ---\n" +
                                "cat /config/migration_services.yaml\n" +
                                "echo ---\n" +
                                "" +
                                command
                            ]
                        }
                    ]
                }
            }
        }
    }
}

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


    .addTemplate("deployConsoleWithConfig", t => t
        .addRequiredInput("command", typeToken<string>())
        .addRequiredInput("configContents", typeToken<z.infer<typeof CONSOLE_SERVICES_CONFIG_FILE>>())
        .addRequiredInput("name", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addResourceTask(b => b
            .setDefinition({
                action: "create",
                setOwnerReference: true,
                successCondition: "status.availableReplicas > 0",
                manifest: getConsoleDeploymentResource(b.inputs.name,
                    b.inputs.imageMigrationConsoleLocation,
                    b.inputs.imageMigrationConsolePullPolicy,
                    expr.toBase64(expr.asString(b.inputs.configContents)),
                    b.inputs.command,
                    DEFAULT_RESOURCES.MIGRATION_CONSOLE_CLI
                )
            }))

        .addJsonPathOutput("deploymentName", "{.metadata.name}", typeToken<string>())
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


    .addTemplate("deployConsole", t => t
        .addRequiredInput("command", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addInputsFromRecord(configComponentParameters)
        .addRequiredInput("name", typeToken<string>())
        .addSteps(s => s
            .addStep("getConsoleConfig", INTERNAL, "getConsoleConfig", c =>
                c.register(selectInputsForRegister(s, c)))
            .addStep("deployConsoleWithConfig", INTERNAL, "deployConsoleWithConfig", c =>
                c.register({
                    ...selectInputsForRegister(s, c),
                    configContents: c.steps.getConsoleConfig.outputs.configContents
                }))
        )
        .addExpressionOutput("deploymentName",
            c => c.steps.getConsoleConfig.outputs.configContents)
    )

    .getFullScope();
