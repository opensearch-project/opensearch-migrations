// TODO

import {WorkflowBuilder} from "@/schemas/workflowBuilder";
import {
    CommonWorkflowParameters,
    makeRequiredImageParametersForKeys
} from "@/workflowTemplates/commonWorkflowTemplates";
import {typeToken} from "@/schemas/sharedTypes";
import {CLUSTER_CONFIG} from "@/workflowTemplates/userSchemas";
import {z} from "zod";

export const CaptureProxy = WorkflowBuilder.create({
    k8sResourceName: "capture-proxy",
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)


    .addTemplate("deployProxyService", t=>t
        .addRequiredInput("serviceName", typeToken<string>())
        .addRequiredInput("port", typeToken<number>())
        .addSteps(b=>b.addStepGroup(c=>c))
    )


    .addTemplate("deployCaptureProxy", t=>t
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof CLUSTER_CONFIG>>())
        .addRequiredInput("listenerPort", typeToken<number>())
        .addRequiredInput("kafkaConnection", typeToken<string>())
        .addRequiredInput("kafkaTopic", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["CaptureProxy"]))

        .addSteps(b=>b.addStepGroup(c=>c)) // TODO convert to a resource!
    )

    .getFullScope();
