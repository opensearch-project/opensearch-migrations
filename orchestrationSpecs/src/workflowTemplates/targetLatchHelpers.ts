import {z} from 'zod';
import {CLUSTER_CONFIG, SNAPSHOT_MIGRATION_CONFIG, SOURCE_MIGRATION_CONFIG} from '@/workflowTemplates/userSchemas'
import {CommonWorkflowParameters, makeRequiredImageParametersForKeys} from "@/workflowTemplates/commonWorkflowTemplates";
import initTlhScript from "resources/targetLatchHelper/init.sh";
import decrementTlhScript from "resources/targetLatchHelper/decrement.sh";
import cleanupTlhScript from "resources/targetLatchHelper/cleanup.sh";
import {TemplateBuilder} from "@/schemas/templateBuilder";
import {WorkflowBuilder} from "@/schemas/workflowBuilder";
import {IMAGE_PULL_POLICY} from "@/schemas/containerBuilder";
import {typeToken} from "@/schemas/sharedTypes";

export const TargetLatchHelpers = WorkflowBuilder.create({
        k8sResourceName: "target-latch-helpers",
        serviceAccountName: "argo-workflow-executor",
        k8sMetadata: {},
        parallelism: 1
    })
    .addParams(CommonWorkflowParameters)
    .addTemplate("init", t => t
        .addInputs(addCommonTargetLatchInputs)
        .addRequiredInput("targets", typeToken<z.infer<typeof CLUSTER_CONFIG>[]>())
        .addRequiredInput("configuration", typeToken<z.infer<typeof SOURCE_MIGRATION_CONFIG>[]>())
        .addContainer(b => b
            .addImageInfo(b.inputs.imageEtcdUtilsLocation, b.inputs.imageEtcdUtilsPullPolicy)
            .addInputsAsEnvVars()
            .addCommand(["sh", "-c"])
            .addArgs([initTlhScript])

            .addPathOutput("prefix", "/tmp/prefix", typeToken<string>())
            .addPathOutput("processorsPerTarget", "/tmp/processors-per-target", typeToken<number>())
        )
    )
    //.templateSigScope.init.input
    .addTemplate("decrementLatch", t => t
        .addInputs(addCommonTargetLatchInputs)
        .addRequiredInput("targetName", typeToken<string>())
        .addRequiredInput("processorId", typeToken<string>())
        .addContainer(b => b
            .addImageInfo(b.inputs.imageEtcdUtilsLocation, b.inputs.imageEtcdUtilsPullPolicy)
            .addInputsAsEnvVars()
            .addCommand(["sh", "-c"])
            .addArgs([decrementTlhScript])

            .addPathOutput("shouldFinalize", "/tmp/should-finalize", typeToken<boolean>())
        )
    )
    .addTemplate("cleanup", t => t
        .addInputs(addCommonTargetLatchInputs)
        .addContainer(b => b
            .addImageInfo(b.inputs.imageEtcdUtilsLocation, b.inputs.imageEtcdUtilsPullPolicy)
            .addInputsAsEnvVars()
            .addCommand(["sh", "-c"])
            .addArgs([cleanupTlhScript])
        )
    )
    .getFullScope();


function addCommonTargetLatchInputs<
    C extends { workflowParameters: typeof CommonWorkflowParameters }
>(tb: TemplateBuilder<C, {}, {}, {}>) {
    return tb
        .addRequiredInput("prefix", typeToken<string>())
        // this makes it much easier to standardize handling in the rest of the template below, rather than pulling
        // now all values can come from input parameters!
        .addOptionalInput("etcdEndpoints", s => s.workflowParameters.etcdEndpoints)
        .addOptionalInput("etcdPassword", s => s.workflowParameters.etcdPassword)
        .addOptionalInput("etcdUser", s => s.workflowParameters.etcdUser)
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["EtcdUtils"]))
        ;
}
