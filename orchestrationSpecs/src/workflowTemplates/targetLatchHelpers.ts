import {z} from 'zod';
import {CLUSTER_CONFIG, IMAGE_PULL_POLICY, SNAPSHOT_MIGRATION_CONFIG} from '@/schemas/userSchemas'
import {CommonWorkflowParameters} from "@/workflowTemplates/commonWorkflowTemplates";
import initTlhScript from "resources/targetLatchHelper/init.sh";
import decrementTlhScript from "resources/targetLatchHelper/decrement.sh";
import cleanupTlhScript from "resources/targetLatchHelper/cleanup.sh";
import {TemplateBuilder} from "@/schemas/templateBuilder";
import {WorkflowBuilder} from "@/schemas/workflowBuilder";
import {typeToken} from "@/schemas/parameterSchemas";

function addCommonTargetLatchInputs<
    C extends { workflowParameters: typeof CommonWorkflowParameters }
>(tb: TemplateBuilder<C, {}, {}, {}>) {
    return tb
        .addRequiredInput("etcdUtilsImagePullPolicy", typeToken<z.infer<typeof IMAGE_PULL_POLICY>>())
        .addRequiredInput("prefix", typeToken<string>())
        // this makes it much easier to standardize handling in the rest of the template below, rather than pulling
        // now all values can come from input parameters!
        .addOptionalInput("etcdEndpoints", s => s.workflowParameters.etcdEndpoints)
        .addOptionalInput("etcdPassword",  s => s.workflowParameters.etcdPassword)
        .addOptionalInput("etcdUser",  s => s.workflowParameters.etcdUser)
        .addOptionalInput("etcdUtilsImage", s => s.workflowParameters.etcdImage);
}

export const TargetLatchHelpers = WorkflowBuilder.create({
        k8sResourceName: "TargetLatchHelpers",
        serviceAccountName: "argo-workflow-executor",
        k8sMetadata: {},
        parallelism: 1
    })
    .addParams(CommonWorkflowParameters)
    .addTemplate("init", t=> t
        .addInputs(addCommonTargetLatchInputs)
        .addRequiredInput("targets", typeToken<z.infer<typeof CLUSTER_CONFIG>[]>())
        .addRequiredInput("configuration", typeToken<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>>())
        .addContainer(b=>b
            .addImageInfo(b.inputs.etcdUtilsImage, b.inputs.etcdUtilsImagePullPolicy)
            .addInputsAsEnvVars()
            .addCommand(["sh", "-c"])
            .addArgs([initTlhScript])

            .addPathOutput("prefix", "/tmp/prefix", z.string())
            .addPathOutput("processorsPerTarget", "/tmp/processors-per-target", z.number())
        )
    )
    //.templateSigScope.init.input
    .addTemplate("decrementLatch", t => t
        .addInputs(addCommonTargetLatchInputs)
        .addRequiredInput("targetName", typeToken<string>())
        .addRequiredInput("processorId", typeToken<string>())
        .addContainer(b=>b
            .addImageInfo(b.inputs.etcdUtilsImage, b.inputs.etcdUtilsImagePullPolicy)
            .addInputsAsEnvVars()
            .addCommand(["sh", "-c"])
            .addArgs([decrementTlhScript])

            .addPathOutput("shouldFinalize", "/tmp/should-finalize", z.boolean())
        )
    )
    .addTemplate("cleanup", t => t
        .addInputs(addCommonTargetLatchInputs)
        .addContainer(b=>b
            .addImageInfo(b.inputs.etcdUtilsImage, b.inputs.etcdUtilsImagePullPolicy)
            .addInputsAsEnvVars()
            .addCommand(["sh", "-c"])
            .addArgs([cleanupTlhScript])
        )
    )
    .getFullScope();
