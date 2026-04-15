import {
    expr,
    INTERNAL,
    selectInputsForRegister,
    typeToken,
    WorkflowBuilder
} from '@opensearch-migrations/argo-workflow-builders';
import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";
import {ResourceManagement} from "./resourceManagement";

/**
 * Workflow Reset: tears down migration resources in dependency order.
 *
 * Design principles:
 *   - Kafka deletion is gated: you cannot delete Kafka unless you also opt in
 *     to deleting the capture proxy (CapturedTraffic CR).
 *   - Deletion proceeds in reverse dependency order:
 *       1. TrafficReplay (replayer depends on proxy + kafka)
 *       2. SnapshotMigration (depends on DataSnapshot)
 *       3. DataSnapshot (depends on snapshot data)
 *       4. CapturedTraffic (proxy — only if includeProxy=true)
 *       5. Kafka resources (only if includeProxy=true, after proxy is deleted)
 *   - A ValidatingAdmissionPolicy enforces at the K8s API level that Kafka
 *     resources cannot be deleted unless they carry a teardown-approval annotation.
 *     The workflow stamps that annotation only after CapturedTraffic is gone.
 *   - All deletes use --ignore-not-found so the workflow is idempotent.
 */
export const ResetMigration = WorkflowBuilder.create({
    k8sResourceName: "reset-migration",
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)


    // ── Step 1: Delete TrafficReplay CRs ─────────────────────────────

    .addTemplate("deleteTrafficReplay", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addSteps(b => b
            .addStep("delete", ResourceManagement, "deleteCrd", c =>
                c.register({
                    resourceKind: expr.literal("TrafficReplay"),
                    resourceName: b.inputs.resourceName,
                })
            )
        )
    )


    // ── Step 2: Delete SnapshotMigration CRs ─────────────────────────

    .addTemplate("deleteSnapshotMigration", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addSteps(b => b
            .addStep("delete", ResourceManagement, "deleteCrd", c =>
                c.register({
                    resourceKind: expr.literal("SnapshotMigration"),
                    resourceName: b.inputs.resourceName,
                })
            )
        )
    )


    // ── Step 3: Delete DataSnapshot CRs ──────────────────────────────

    .addTemplate("deleteDataSnapshot", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addSteps(b => b
            .addStep("delete", ResourceManagement, "deleteCrd", c =>
                c.register({
                    resourceKind: expr.literal("DataSnapshot"),
                    resourceName: b.inputs.resourceName,
                })
            )
        )
    )


    // ── Step 4: Delete CapturedTraffic (proxy) CRs ───────────────────

    .addTemplate("deleteCapturedTraffic", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addSteps(b => b
            .addStep("delete", ResourceManagement, "deleteCrd", c =>
                c.register({
                    resourceKind: expr.literal("CapturedTraffic"),
                    resourceName: b.inputs.resourceName,
                })
            )
        )
    )


    // ── Step 5: Approve + delete Kafka resources ─────────────────────
    // Stamps the teardown-approval annotation on the Kafka cluster,
    // then deletes topic, node pool, and cluster in order.

    .addTemplate("teardownKafka", t => t
        .addRequiredInput("kafkaClusterName", typeToken<string>())
        .addRequiredInput("kafkaTopicName", typeToken<string>())
        .addRequiredInput("kafkaNodePoolName", typeToken<string>())
        .addSteps(b => b
            .addStep("approveKafkaTeardown", ResourceManagement, "patchTeardownApprovalAnnotation", c =>
                c.register({
                    resourceApiVersion: expr.literal("kafka.strimzi.io/v1"),
                    resourceKind: expr.literal("Kafka"),
                    resourceName: b.inputs.kafkaClusterName,
                })
            )
            .addStep("deleteTopic", ResourceManagement, "deleteKafkaTopic", c =>
                c.register({
                    resourceName: b.inputs.kafkaTopicName,
                })
            )
            .addStep("deleteNodePool", ResourceManagement, "deleteKafkaNodePool", c =>
                c.register({
                    resourceName: b.inputs.kafkaNodePoolName,
                })
            )
            .addStep("deleteCluster", ResourceManagement, "deleteKafkaCluster", c =>
                c.register({
                    resourceName: b.inputs.kafkaClusterName,
                })
            )
        )
    )


    // ── Entrypoint: resetWorkflow ────────────────────────────────────
    // Orchestrates the full reset in dependency order.
    //
    // includeProxy=true  => deletes everything including proxy + kafka
    // includeProxy=false => deletes only replay + snapshot resources

    .addTemplate("resetWorkflow", t => t
        .addRequiredInput("trafficReplayName", typeToken<string>())
        .addRequiredInput("snapshotMigrationName", typeToken<string>())
        .addRequiredInput("dataSnapshotName", typeToken<string>())
        .addRequiredInput("capturedTrafficName", typeToken<string>())
        .addRequiredInput("kafkaClusterName", typeToken<string>())
        .addRequiredInput("kafkaTopicName", typeToken<string>())
        .addRequiredInput("kafkaNodePoolName", typeToken<string>())
        .addRequiredInput("includeProxy", typeToken<boolean>())

        .addSteps(b => b
            // Phase 1: Delete replayer (depends on proxy + kafka, so goes first)
            .addStep("deleteReplay", INTERNAL, "deleteTrafficReplay", c =>
                c.register({
                    resourceName: b.inputs.trafficReplayName,
                })
            )
            // Phase 2: Delete snapshot-related CRDs (no dependency on proxy)
            .addStepGroup(g => g
                .addStep("deleteSnapshotMigration", INTERNAL, "deleteSnapshotMigration", c =>
                    c.register({
                        resourceName: b.inputs.snapshotMigrationName,
                    })
                )
                .addStep("deleteDataSnapshot", INTERNAL, "deleteDataSnapshot", c =>
                    c.register({
                        resourceName: b.inputs.dataSnapshotName,
                    })
                )
            )
            // Phase 3: Delete proxy (only if includeProxy=true)
            .addStep("deleteProxy", INTERNAL, "deleteCapturedTraffic", c =>
                c.register({
                    resourceName: b.inputs.capturedTrafficName,
                }),
                { when: () => expr.equals(expr.asString(b.inputs.includeProxy), expr.literal("true")) }
            )
            // Phase 4: Tear down Kafka (only if includeProxy=true)
            .addStep("teardownKafka", INTERNAL, "teardownKafka", c =>
                c.register({
                    kafkaClusterName: b.inputs.kafkaClusterName,
                    kafkaTopicName: b.inputs.kafkaTopicName,
                    kafkaNodePoolName: b.inputs.kafkaNodePoolName,
                }),
                { when: () => expr.equals(expr.asString(b.inputs.includeProxy), expr.literal("true")) }
            )
        )
    )


    .getFullScope();
