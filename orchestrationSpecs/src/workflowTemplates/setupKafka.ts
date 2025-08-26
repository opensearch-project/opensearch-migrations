import {WorkflowBuilder} from "@/schemas/workflowBuilder";
import {CommonWorkflowParameters} from "@/workflowTemplates/commonWorkflowTemplates";
import {z} from "zod";

export const SetupKafka = WorkflowBuilder.create({
    k8sResourceName: "SetupKafka",
    serviceAccountName: "argo-workflow-executor",
    k8sMetadata: {},
    parallelism: 1
})
    .addParams(CommonWorkflowParameters)
    .addTemplate("clusterDeploy", t=> t
        .addRequiredInput("kafkaName", z.string())
        .addOptionalInput("useKraft", s=>true)
        .addDag(b=>b)
    )
);
