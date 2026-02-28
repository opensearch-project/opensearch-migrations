
import {z} from "zod";
import {
    ARGO_CREATE_SNAPSHOT_OPTIONS,
    CLUSTER_CONFIG,
    COMPLETE_SNAPSHOT_CONFIG,
    CONSOLE_SERVICES_CONFIG_FILE,
    DEFAULT_RESOURCES,
    NAMED_SOURCE_CLUSTER_CONFIG_WITHOUT_SNAPSHOT_INFO,
} from "@opensearch-migrations/schemas";
import {MigrationConsole} from "./migrationConsole";
import {ResourceManagement} from "./resourceManagement";
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
import {makeClusterParamDict} from "./commonUtils/clusterSettingManipulators";
import {getHttpAuthSecretName} from "./commonUtils/clusterSettingManipulators";
import {getSourceHttpAuthCreds, getTargetHttpAuthCreds} from "./commonUtils/basicCredsGetters";

const checkScript = `
  set -e
  touch /tmp/status-output.txt
  touch /tmp/phase-output.txt
  
  # Quick status check - if SUCCESS, we're done
  status=$(console --config-file=/config/migration_services.yaml snapshot status)
  if [ "$status" = "SUCCESS" ]; then
    echo "Snapshot completed successfully" > /tmp/status-output.txt
    exit 0
  fi
  
  # Deep check and save output in case there was a race condition
  deep_output=$(console --config-file=/config/migration_services.yaml snapshot status --deep-check)
  
  # Check if deep check also returned SUCCESS (snapshot completed during execution)
  if [ "$deep_output" = "SUCCESS" ]; then
    echo "Snapshot completed successfully" > /tmp/status-output.txt
    exit 0
  fi
  
  # Process deep check output with awk for in-progress snapshots
  echo "$deep_output" | awk '
    /Total shards:/ { total = $3 }
    /Successful shards:/ { successful = $3 }
    /Data processed:/ { data = $3; unit = $4 }
    /Estimated time to completion:/ { 
      sub(/.*: /, "");
      eta = $0
    }
    END {
      if (total) {
        output = "Shards: " successful "/" total " | Data: " data " " unit
        if (eta != "0h 0m 0s") {
          output = output " | ETA: " eta
        }
        print output
      }
    }
  ' > /tmp/status-output.txt
  echo Checked > /tmp/phase-output.txt
  
  exit 1
`.trim();

export function makeSourceParamDict(sourceConfig: BaseExpression<Serialized<z.infer<typeof CLUSTER_CONFIG>>>) {
    return makeClusterParamDict("source", sourceConfig);
}

function makeParamsDict(
    sourceConfig: BaseExpression<Serialized<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG_WITHOUT_SNAPSHOT_INFO>>>,
    snapshotConfig: BaseExpression<Serialized<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>>,
    options: BaseExpression<Serialized<z.infer<typeof ARGO_CREATE_SNAPSHOT_OPTIONS>>>
) {
    return expr.mergeDicts(
        expr.mergeDicts(
            makeSourceParamDict(sourceConfig),
            expr.mergeDicts(
                expr.omit(expr.deserializeRecord(options), "loggingConfigurationOverrideConfigMap", "jvmArgs"),
                // noWait is essential for workflow logic - the workflow handles polling for snapshot
                // completion separately via checkSnapshotStatus, so the CreateSnapshot command must
                // return immediately to allow the workflow to manage the wait/retry behavior
                expr.makeDict({
                    "noWait": expr.literal(true)
                })
            )
        ),
        expr.mergeDicts(
            expr.makeDict({
                "snapshotName": expr.get(expr.deserializeRecord(snapshotConfig), "snapshotName"),
                "snapshotRepoName": expr.jsonPathStrict(snapshotConfig, "repoConfig", "repoName")
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
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG_WITHOUT_SNAPSHOT_INFO>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("createSnapshotConfig", typeToken<z.infer<typeof ARGO_CREATE_SNAPSHOT_OPTIONS>>())
        .addRequiredInput("sourceK8sLabel", typeToken<string>())
        .addRequiredInput("targetK8sLabel", typeToken<string>())
        .addRequiredInput("snapshotK8sLabel", typeToken<string>())
        .addOptionalInput("taskK8sLabel", c => "snapshot")

        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addContainer(b=>b
            .addImageInfo(b.inputs.imageMigrationConsoleLocation, b.inputs.imageMigrationConsolePullPolicy)
            .addCommand(["/root/createSnapshot/bin/CreateSnapshot"])
            .addEnvVarsFromRecord(getSourceHttpAuthCreds(getHttpAuthSecretName(b.inputs.sourceConfig)))
            .addEnvVar("JDK_JAVA_OPTIONS",
                expr.dig(expr.deserializeRecord(b.inputs.createSnapshotConfig), ["jvmArgs"], "")
            )
            .addResources(DEFAULT_RESOURCES.JAVA_MIGRATION_CONSOLE_CLI)
            .addArgs([
                expr.literal("---INLINE-JSON"),
                expr.asString(expr.serialize(
                    makeParamsDict(b.inputs.sourceConfig, b.inputs.snapshotConfig, b.inputs.createSnapshotConfig)
                ))
            ])
            .addPodMetadata(({ inputs }) => ({
                labels: {
                    'migrations.opensearch.org/source': inputs.sourceK8sLabel,
                    'migrations.opensearch.org/target': inputs.targetK8sLabel,
                    'migrations.opensearch.org/snapshot': inputs.snapshotK8sLabel,
                    'migrations.opensearch.org/task': inputs.taskK8sLabel
                }
            }))
        )

    )

    .addTemplate("checkSnapshotStatusInternal", t => t
        .addRequiredInput("configContents", typeToken<z.infer<typeof CONSOLE_SERVICES_CONFIG_FILE>>())
        .addRequiredInput("sourceK8sLabel", typeToken<string>())
        .addRequiredInput("targetK8sLabel", typeToken<string>())
        .addRequiredInput("snapshotK8sLabel", typeToken<string>())
        .addOptionalInput("taskK8sLabel", c => "snapshotStatusCheck")
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addSteps(b => b
            .addStep("checkSnapshotCompletion", MigrationConsole, "runMigrationCommandForStatus", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    command: checkScript
                }))
        )
        .addRetryParameters({
            limit: "200", retryPolicy: "Always",
            backoff: {duration: "5", factor: "2", cap: "300"}
        })
    )

    .addTemplate("checkSnapshotStatus", t => t
        .addRequiredInput("configContents", typeToken<z.infer<typeof CONSOLE_SERVICES_CONFIG_FILE>>())
        .addRequiredInput("sourceK8sLabel", typeToken<string>())
        .addRequiredInput("targetK8sLabel", typeToken<string>())
        .addRequiredInput("snapshotK8sLabel", typeToken<string>())
        .addOptionalInput("groupName", c => "checks")
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addSteps(b => b
            .addStep("runStatusChecks", INTERNAL, "checkSnapshotStatusInternal", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    configContents: b.inputs.configContents,
                    sourceK8sLabel: b.inputs.sourceK8sLabel,
                    targetK8sLabel: b.inputs.targetK8sLabel,
                    snapshotK8sLabel: b.inputs.snapshotK8sLabel
                }))
        )
    )


    .addTemplate("snapshotWorkflow", t => t
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG_WITHOUT_SNAPSHOT_INFO>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("createSnapshotConfig", typeToken<z.infer<typeof ARGO_CREATE_SNAPSHOT_OPTIONS>>())
        .addRequiredInput("targetLabel", typeToken<string>())
        .addRequiredInput("semaphoreConfigMapName", typeToken<string>())
        .addRequiredInput("semaphoreKey", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addSteps(b => b
            .addStep("createSnapshot", INTERNAL, "runCreateSnapshot", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    sourceK8sLabel: expr.jsonPathStrict(b.inputs.sourceConfig, "label"),
                    targetK8sLabel: b.inputs.targetLabel,
                    snapshotK8sLabel: expr.jsonPathStrict(b.inputs.snapshotConfig, "label")
                }))

            .addStep("getConsoleConfig", MigrationConsole, "getConsoleConfig", c =>
                c.register({
                    ...selectInputsForRegister(b, c)
                }))

            .addStep("waitForCompletion", INTERNAL, "checkSnapshotStatus", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    configContents: c.steps.getConsoleConfig.outputs.configContents,
                    sourceK8sLabel: expr.jsonPathStrict(b.inputs.sourceConfig, "label"),
                    targetK8sLabel: b.inputs.targetLabel,
                    snapshotK8sLabel: expr.jsonPathStrict(b.inputs.snapshotConfig, "label")
                }))

            .addStep("patchDataSnapshot", ResourceManagement, "patchDataSnapshotReady", c =>
                c.register({
                    resourceName: expr.concat(
                        expr.jsonPathStrict(b.inputs.sourceConfig, "label"),
                        expr.literal("-"),
                        expr.jsonPathStrict(b.inputs.snapshotConfig, "label")
                    ),
                    snapshotName: expr.jsonPathStrict(b.inputs.snapshotConfig, "snapshotName")
                }))
        )
        .addSynchronization(c => ({
            semaphores: [{
                configMapKeyRef: {
                    name: c.inputs.semaphoreConfigMapName,
                    key: c.inputs.semaphoreKey
                }
            }]
        }))
        .addExpressionOutput("snapshotConfig", b => b.inputs.snapshotConfig)
    )


    .getFullScope();

