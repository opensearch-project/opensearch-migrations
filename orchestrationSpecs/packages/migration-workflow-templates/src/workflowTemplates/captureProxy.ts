// TODO

import {typeToken, WorkflowBuilder} from "@opensearch-migrations/argo-workflow-builders";
import {CLUSTER_CONFIG} from "@opensearch-migrations/schemas";
import {z} from "zod";

import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";
import {makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";

export const CaptureProxy = WorkflowBuilder.create({
    k8sResourceName: "capture-proxy",
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)


    .addTemplate("deployProxyService", t => t
        .addRequiredInput("serviceName", typeToken<string>())
        .addRequiredInput("port", typeToken<number>())
        .addSteps(b => b.addStepGroup(c => c))
    )


    .addTemplate("deployCaptureProxy", t => t
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof CLUSTER_CONFIG>>())
        .addRequiredInput("listenerPort", typeToken<number>())
        .addRequiredInput("kafkaConnection", typeToken<string>())
        .addRequiredInput("kafkaTopic", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["CaptureProxy"]))

        .addSteps(b => b.addStepGroup(c => c)) // TODO convert to a resource!
    )

    .getFullScope();
