import {
    INTERNAL,
    selectInputsForRegister,
    typeToken,
    WorkflowBuilder
} from "@opensearch-migrations/argo-workflow-builders";
import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";
import {SetupKafka} from "./setupKafka";
import {
    NAMED_KAFKA_CLUSTER_CONFIG,
    PROXY_OPTIONS
} from "@opensearch-migrations/schemas";
import {z} from "zod";

export const SetupCapture = WorkflowBuilder.create({
    k8sResourceName: "setup-capture",
    parallelism: 100,
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)


    .addTemplate("setupProxy", t => t
        .addRequiredInput("proxySettings", typeToken<z.infer<typeof PROXY_OPTIONS>>())
        .addRequiredInput("kafkaName", typeToken<string>())
        .addRequiredInput("proxyName", typeToken<string>())

        .addSteps(b=>b
            .addStep("createKafkaTopic", SetupKafka, "createKafkaTopic", c => c.register({
                    ...selectInputsForRegister(b, c),
                    clusterName: b.inputs.kafkaName,
                    topicName: b.inputs.proxyName,
                    topicPartitions: 1,
                    topicReplicas: 3
                })
            )
        )
    )


    .getFullScope();