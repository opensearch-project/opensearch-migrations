import {z} from 'zod';
import {CLUSTER_CONFIG, IMAGE_PULL_POLICY, IMAGE_SPECIFIER, SNAPSHOT_MIGRATION_CONFIG} from '@/schemas/userSchemas'
import {CommonWorkflowParameters} from "@/workflowTemplates/commonWorkflowTemplates";
import initTlhScript from "resources/targetLatchHelper/init.sh";
import decrementTlhScript from "resources/targetLatchHelper/decrement.sh";
import cleanupTlhScript from "resources/targetLatchHelper/cleanup.sh";
import {TemplateBuilder} from "@/schemas/templateBuilder";
import {ContainerBuilder} from "@/schemas/containerBuilder";
import {WorkflowBuilder} from "@/schemas/workflowBuilder";
import {GenericScope} from "@/schemas/workflowTypes";
import {InputParametersRecord} from "@/schemas/parameterSchemas";

function addCommonTargetLatchInputs<
    C extends { workflowParameters: typeof CommonWorkflowParameters }
>(tb: TemplateBuilder<C, {}, {}, {}>) {
    return tb
        .addRequiredInput("prefix", z.string())
        .addRequiredInput("etcdUtilsImagePullPolicy", IMAGE_PULL_POLICY)
        // this makes it much easier to standardize handling in the rest of the template below, rather than pulling
        // now all values can come from input parameters!
        .addOptionalInput("etcdEndpoints", s => s.workflowParameters.etcdEndpoints)
        .addOptionalInput("etcdPassword",  s => s.workflowParameters.etcdPassword)
        .addOptionalInput("etcdUser",  s => s.workflowParameters.etcdUser)
        .addOptionalInput("etcdUtilsImage", s => s.workflowParameters.etcdImage)
    ;
}

function commonTargetLatchHelperEnvVarsFromWorkflowParameters<
    ContextualScope extends { workflowParameters: typeof CommonWorkflowParameters },
    InputParamsScope extends InputParametersRecord,
    ContainerScope extends GenericScope
>(
    b: ContainerBuilder<ContextualScope, InputParamsScope, ContainerScope, {}, {}>
) {
    return b
        .addEnvVar("ETCD_ENDPOINTS", b.workflowInputs.etcdEndpoints)
        .addEnvVar("ETCD_PASSWORD", b.workflowInputs.etcdPassword)
        .addEnvVar("ETCD_USER", b.workflowInputs.etcdUser);
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
        .addRequiredInput("targets", z.array(CLUSTER_CONFIG))
        .addRequiredInput("configuration", SNAPSHOT_MIGRATION_CONFIG)
        .addContainer(b=>b
            .addImageInfo(b.inputs.etcdUtilsImage, b.inputs.etcdUtilsImagePullPolicy)
            .addInputsAsEnvVars()
            .addCommand(["sh", "-c"])
            .addArgs([initTlhScript])

            .addPathOutput("prefix", "/tmp/prefix", z.string())
            .addPathOutput("processorsPerTarget", "/tmp/processors-per-target", z.number())
        )
    )
    .addTemplate("decrementLatch", t => t
        .addInputs(addCommonTargetLatchInputs)
        .addRequiredInput("targetName", CLUSTER_CONFIG)
        .addRequiredInput("processorId", z.string())
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
            .addPathOutput("DUMMY_NEED_TO_RESOLVE_THIS_BUILDER BUG!", "/tmp/should-finalize", z.boolean())
        )
    )
    .getFullScope();
