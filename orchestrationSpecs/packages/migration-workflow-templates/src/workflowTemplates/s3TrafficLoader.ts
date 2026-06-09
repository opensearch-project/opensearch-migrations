import {z} from "zod";
import {
    DEFAULT_RESOURCES,
    DENORMALIZED_S3_TRAFFIC_LOADER_CONFIG,
} from "@opensearch-migrations/schemas";
import {
    expr,
    INTERNAL,
    selectInputsForRegister,
    Serialized,
    typeToken,
    WorkflowBuilder,
} from "@opensearch-migrations/argo-workflow-builders";
import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";
import {makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";
import {SetupKafka} from "./setupKafka";
import {ResourceManagement} from "./resourceManagement";
import {CONTAINER_TEMPLATE_RETRY_STRATEGY} from "./commonUtils/resourceRetryStrategy";
import {BaseExpression} from "@opensearch-migrations/argo-workflow-builders";

// Same shape used by setupCapture / fullMigration: skip a step if the CR's
// current checksum already matches what we'd write. For the s3 loader path,
// once `loadS3IntoTopic` exits zero it patches both `configChecksum` and
// `checksumForReplayer` to the loader's input checksum — so on a clean
// resubmit, this returns false and the loader is skipped.
function checksumNotDone(
    actualChecksum: BaseExpression<string>,
    desiredChecksum: BaseExpression<string>,
): BaseExpression<boolean, "complicatedExpression"> {
    return expr.and(expr.literal(true), expr.not(expr.equals(actualChecksum, desiredChecksum)));
}

// One-time loader: streams s3://...proto.gz → gunzip → kafkaUtils onto a
// Kafka topic, then patches the CapturedTraffic CR to phase=Ready with
// loadCompleted=true. The loader runs exactly once per CapturedTraffic
// resource — re-runs are blocked by a VAP (lock-on-complete +
// loadStarted-cannot-unset) so partial loads can only be recovered by
// `kubectl delete capturedtraffic <name>`, mirroring the design in
// docs/BringYourOwnCapturedTraffic.md.
export const S3TrafficLoader = WorkflowBuilder.create({
    k8sResourceName: "s3-traffic-loader",
    parallelism: 100,
    serviceAccountName: "argo-workflow-executor",
    k8sMetadata: {},
})
    .addParams(CommonWorkflowParameters)


    // The container task. Uses the migration-console image (which already
    // bundles kafkaUtils + the aws CLI + kafka-tools/) and invokes the
    // documented kafkaImport.sh recipe.
    .addTemplate("loadS3IntoTopic", t => t
        .addRequiredInput("topicCrName", typeToken<string>())
        .addRequiredInput("topicName", typeToken<string>())
        .addRequiredInput("brokers", typeToken<string>())
        .addRequiredInput("s3Uri", typeToken<string>())
        .addRequiredInput("awsRegion", typeToken<string>())
        .addOptionalInput("endpoint", c => "")
        .addOptionalInput("kafkaAuthType", c => "none")
        .addRequiredInput("loadCompletionChecksum", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addContainer(b => b
            .addImageInfo(b.inputs.imageMigrationConsoleLocation, b.inputs.imageMigrationConsolePullPolicy)
            .addCommand(["/bin/bash", "-lc"])
            .addResources(DEFAULT_RESOURCES.SHELL_MIGRATION_CONSOLE_CLI)
            // For LocalStack S3 testing the loader gets credentials from the
            // localstack-test-creds ConfigMap (same pattern as createSnapshot.ts).
            // For production, the service account's IAM role is used directly
            // (no override needed). The configmap's `optional: true` means
            // production runs simply skip the mount.
            .addVolumesFromRecord({
                'test-creds': {
                    configMap: {
                        name: expr.literal("localstack-test-creds"),
                        optional: true,
                    },
                    mountPath: "/config/credentials",
                    readOnly: true,
                },
            })
            .addEnvVar("AWS_SHARED_CREDENTIALS_FILE",
                expr.ternary(
                    expr.not(expr.isEmpty(b.inputs.endpoint)),
                    expr.literal("/config/credentials/configuration"),
                    expr.literal("")
                )
            )
            .addEnvVar("AWS_REGION", b.inputs.awsRegion)
            .addEnvVar("AWS_DEFAULT_REGION", b.inputs.awsRegion)
            .addEnvVar("S3_URI", b.inputs.s3Uri)
            .addEnvVar("S3_ENDPOINT", b.inputs.endpoint)
            .addEnvVar("BROKERS", b.inputs.brokers)
            .addEnvVar("TOPIC", b.inputs.topicName)
            .addEnvVar("CT_NAME", b.inputs.topicCrName)
            .addEnvVar("LOAD_CHECKSUM", b.inputs.loadCompletionChecksum)
            .addEnvVar("KAFKA_AUTH_TYPE", b.inputs.kafkaAuthType)
            .addArgs([`
set -euo pipefail

# Optional LocalStack endpoint override for kafkaImport.sh / aws CLI.
S3_ENDPOINT_FLAG=""
if [[ -n "$S3_ENDPOINT" ]]; then
    S3_ENDPOINT_FLAG="--endpoint-url $S3_ENDPOINT"
fi

START_TS=$(date -u +%FT%TZ)

# kafkaImport.sh streams S3 -> gunzip -> kafkaUtils --stdin into the topic.
MIGRATION_KAFKA_BROKER_ENDPOINTS="$BROKERS" \\
  /root/kafka-tools/kafkaImport.sh \\
    --topic "$TOPIC" \\
    --s3-uri "$S3_URI" \\
    \${S3_ENDPOINT:+--endpoint-url "$S3_ENDPOINT"} \\
    \${KAFKA_AUTH_TYPE:+--auth-type "$KAFKA_AUTH_TYPE"}

END_TS=$(date -u +%FT%TZ)

# On clean exit, patch CapturedTraffic to Ready with loadCompleted=true and
# observability metadata. The status patch happens here (in the loader
# container) rather than as a separate Argo step so a hard kill mid-load
# leaves the CR in loadStarted=true / loadCompleted=absent state, which the
# next workflow run sees and rejects (forcing a manual reset).
kubectl patch capturedtraffic "$CT_NAME" \\
  --type merge --subresource=status -p '{
    "status": {
      "phase": "Ready",
      "loadCompleted": true,
      "configChecksum": "'"$LOAD_CHECKSUM"'",
      "checksumForReplayer": "'"$LOAD_CHECKSUM"'",
      "loadStats": {
        "sourceUri": "'"$S3_URI"'",
        "completedAt": "'"$END_TS"'"
      }
    }
  }'
`])
        )
        .addRetryParameters(CONTAINER_TEMPLATE_RETRY_STRATEGY)
    )


    // Top-level: reconcile the CapturedTraffic + topic, then run the loader.
    // Mirrors setupCapture.reconcileCaptureTopicAndProxy phase 1 but stops
    // BEFORE patching Ready — the loader itself patches Ready on success.
    .addTemplate("reconcileCapturedTrafficAndLoad", t => t
        .addRequiredInput("loaderConfig", typeToken<z.infer<typeof DENORMALIZED_S3_TRAFFIC_LOADER_CONFIG>>())
        .addRequiredInput("kafkaClusterName", typeToken<string>())
        .addRequiredInput("kafkaTopicName", typeToken<string>())
        .addRequiredInput("topicCrName", typeToken<string>())
        .addRequiredInput("configChecksum", typeToken<string>())
        .addRequiredInput("topicConfigChecksum", typeToken<string>())
        .addRequiredInput("checksumForSnapshot", typeToken<string>())
        .addRequiredInput("checksumForReplayer", typeToken<string>())
        .addRequiredInput("topicPartitions", typeToken<number>())
        .addRequiredInput("topicReplicas", typeToken<number>())
        .addRequiredInput("topicConfig", typeToken<Serialized<Record<string, any>>>())
        .addRequiredInput("kafkaClusterOwnerUid", typeToken<string>())
        .addRequiredInput("sourceK8sLabel", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addSteps(b => {
            const loaderCfg = expr.deserializeRecord(b.inputs.loaderConfig);
            const managedByWorkflow = expr.dig(loaderCfg, ["kafkaConfig", "managedByWorkflow"], false);
            const s3Uri = expr.get(loaderCfg, "s3Uri");
            const awsRegion = expr.get(loaderCfg, "awsRegion");
            const endpoint = expr.dig(loaderCfg, ["endpoint"], "");
            const kafkaAuthType = expr.dig(loaderCfg, ["kafkaConfig", "authType"], "none");
            const brokers = expr.dig(loaderCfg, ["kafkaConfig", "kafkaConnection"], "");

            return b
            // The apply here is the gate the VAP will police on resubmits.
            // Including sourceKind + s3SourceUri in the spec means a config
            // change (different URI, switching source kinds) is detected at
            // admission and rejected before the loader ever starts.
            .addStep("reconcileCapturedTrafficResource", ResourceManagement, "reconcileCapturedTrafficResource", c =>
                c.register({
                    topicCrName: b.inputs.topicCrName,
                    kafkaClusterName: b.inputs.kafkaClusterName,
                    kafkaTopicName: b.inputs.kafkaTopicName,
                    sourceLabel: b.inputs.sourceK8sLabel,
                    partitions: b.inputs.topicPartitions,
                    replicas: b.inputs.topicReplicas,
                    topicConfig: b.inputs.topicConfig,
                    sourceKind: expr.literal("s3"),
                    s3SourceUri: s3Uri,
                    configChecksum: b.inputs.checksumForReplayer,
                    retryGateName: expr.concat(expr.literal("capturedtraffic."), b.inputs.topicCrName, expr.literal(".vapretry")),
                    retryGroupName_view: expr.concat(expr.literal("CapturedTraffic: "), b.inputs.topicCrName),
                })
            )
            .addStep("waitForKafkaCluster", SetupKafka, "waitForKafkaCluster", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    resourceName: b.inputs.kafkaClusterName,
                }),
                { when: c => ({templateExp: expr.and(
                    checksumNotDone(c.reconcileCapturedTrafficResource.outputs.currentConfigChecksum, b.inputs.checksumForReplayer),
                    managedByWorkflow
                )}) }
            )
            .addStep("createKafkaTopic", SetupKafka, "createKafkaTopic", c =>
                c.register({
                    clusterName: b.inputs.kafkaClusterName,
                    topicName: b.inputs.kafkaTopicName,
                    migrationRunNumber: t.inputs.workflowParameters.migrationRunNumber,
                    ownerUid: b.inputs.kafkaClusterOwnerUid,
                    sourceLabel: b.inputs.sourceK8sLabel,
                    partitions: b.inputs.topicPartitions,
                    replicas: b.inputs.topicReplicas,
                    topicConfig: b.inputs.topicConfig,
                }),
                { when: c => ({templateExp: expr.and(
                    checksumNotDone(c.reconcileCapturedTrafficResource.outputs.currentConfigChecksum, b.inputs.checksumForReplayer),
                    managedByWorkflow
                )}) }
            )
            // Loader runs ONCE per CR. The success-side patch in the loader
            // sets status.checksumForReplayer to this same input value, so
            // on a clean resubmit `currentConfigChecksum == checksumForReplayer`
            // and the `when` clause skips the loader entirely.
            //
            // NOTE: NO patchCapturedTrafficReady here — the loader itself
            // patches phase=Ready + loadCompleted=true on success. If the loader
            // pod is killed mid-run, the CR stays not-Ready, the VAP-locked
            // s3SourceUri prevents config drift, and the user must delete the
            // CR to recover (matches the design's exactly-once semantics).
            .addStep("loadS3IntoTopic", INTERNAL, "loadS3IntoTopic", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    topicCrName: b.inputs.topicCrName,
                    topicName: b.inputs.kafkaTopicName,
                    brokers: brokers,
                    s3Uri: s3Uri,
                    awsRegion: awsRegion,
                    endpoint: endpoint,
                    kafkaAuthType: kafkaAuthType,
                    loadCompletionChecksum: b.inputs.checksumForReplayer,
                }),
                { when: c => ({templateExp: checksumNotDone(
                    c.reconcileCapturedTrafficResource.outputs.currentConfigChecksum,
                    b.inputs.checksumForReplayer
                )}) }
            );
        })
    )


    .getFullScope();
