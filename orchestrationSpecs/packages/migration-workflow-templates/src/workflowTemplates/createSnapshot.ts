
import {z} from "zod";
import {
    ARGO_CREATE_SNAPSHOT_OPTIONS,
    CLUSTER_CONFIG,
    COMPLETE_SNAPSHOT_CONFIG,
    CONSOLE_SERVICES_CONFIG_FILE,
    DEFAULT_RESOURCES,
    NAMED_SOURCE_CLUSTER_CONFIG,
} from "@opensearch-migrations/schemas";
import {MigrationConsole} from "./migrationConsole";
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
import {extractSourceKeysToExpressionMap, makeClusterParamDict} from "./commonUtils/clusterSettingManipulators";
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
  echo Running > /tmp/phase-output.txt
  
  exit 1
`.trim();

export function makeSourceParamDict(sourceConfig: BaseExpression<Serialized<z.infer<typeof CLUSTER_CONFIG>>>) {
    return makeClusterParamDict("source", sourceConfig);
}

function makeParamsDict(
    sourceConfig: BaseExpression<Serialized<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG>>>,
    snapshotConfig: BaseExpression<Serialized<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>>,
    options: BaseExpression<Serialized<z.infer<typeof ARGO_CREATE_SNAPSHOT_OPTIONS>>>
) {
    return expr.mergeDicts(
        expr.mergeDicts(
            makeSourceParamDict(sourceConfig),
            expr.mergeDicts(
                expr.omit(expr.deserializeRecord(options),
                    "loggingConfigurationOverrideConfigMap", "semaphoreConfigMapName", "semaphoreKey"),
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
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("createSnapshotConfig", typeToken<z.infer<typeof ARGO_CREATE_SNAPSHOT_OPTIONS>>())

        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addContainer(b=>b
            .addImageInfo(b.inputs.imageMigrationConsoleLocation, b.inputs.imageMigrationConsolePullPolicy)
            .addCommand(["/root/createSnapshot/bin/CreateSnapshot"])
            .addEnvVarsFromRecord(getSourceHttpAuthCreds(getHttpAuthSecretName(b.inputs.sourceConfig)))
            .addResources(DEFAULT_RESOURCES.MIGRATION_CONSOLE_CLI)
            .addArgs([
                expr.literal("---INLINE-JSON"),
                expr.asString(expr.serialize(
                    makeParamsDict(b.inputs.sourceConfig, b.inputs.snapshotConfig, b.inputs.createSnapshotConfig)
                ))
            ])
        )

    )

    .addTemplate("checkSnapshotStatus", t => t
        .addRequiredInput("configContents", typeToken<z.infer<typeof CONSOLE_SERVICES_CONFIG_FILE>>())
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
        // .addExpressionOutput("statusOutput", b => b.steps.checkSnapshotCompletion.outputs.statusOutput)
        // .addExpressionOutput("overriddenPhase", b => b.steps.checkSnapshotCompletion.outputs.overriddenPhase)
    )


    .addTemplate("snapshotWorkflow", t => t
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("createSnapshotConfig", typeToken<z.infer<typeof ARGO_CREATE_SNAPSHOT_OPTIONS>>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addSteps(b => b
            .addStep("createSnapshot", INTERNAL, "runCreateSnapshot", c =>
                c.register(selectInputsForRegister(b, c)))

            .addStep("getConsoleConfig", MigrationConsole, "getConsoleConfig", c =>
                c.register({
                    ...selectInputsForRegister(b, c)
                }))

            .addStep("checkSnapshotStatus", INTERNAL, "checkSnapshotStatus", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    configContents: c.steps.getConsoleConfig.outputs.configContents
                }))
        )
        .addSynchronization(c => ({
            semaphores: [{
                configMapKeyRef: {
                    name: expr.get(expr.deserializeRecord(c.inputs.createSnapshotConfig), "semaphoreConfigMapName"),
                    key: expr.get(expr.deserializeRecord(c.inputs.createSnapshotConfig), "semaphoreKey")
                }
            }]
        }))
        .addExpressionOutput("snapshotConfig", b => b.inputs.snapshotConfig)
    )


    .getFullScope();

