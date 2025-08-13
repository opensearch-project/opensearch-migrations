import {z} from 'zod';
import {CLUSTER_CONFIG, IMAGE_PULL_POLICY, IMAGE_SPECIFIER, SNAPSHOT_MIGRATION_CONFIG} from '@/schemas/userSchemas'
import {CommonWorkflowParameters} from "@/workflowTemplates/commonWorkflowTemplates";
import {Scope} from "@/schemas/workflowTypes";
import {asString} from "@/schemas/expression";
import initTlhScript from "resources/targetLatchHelper/init.sh";
import decrementTlhScript from "resources/targetLatchHelper/decrement.sh";
import cleanupTlhScript from "resources/targetLatchHelper/cleanup.sh";
import {TemplateBuilder} from "@/schemas/templateBuilder";
import {ContainerBuilder} from "@/schemas/containerBuilder";
import {WorkflowBuilder} from "@/schemas/workflowBuilder";

function addCommonTargetLatchInputs<C extends Scope>(tb: TemplateBuilder<C, {}, {}, {}>) {
    return tb
        .addRequiredInput("prefix", z.string())
        .addRequiredInput("etcdUtilsImage", z.string())
        .addRequiredInput("etcdUtilsImagePullPolicy", IMAGE_PULL_POLICY);
}

function commonTargetLatchHelperEnvVarsFromWorkflowParameters<
    ContextualScope extends { workflowParameters: typeof CommonWorkflowParameters },
    InputParamsScope extends Scope,
    ContainerScope extends Scope,
    OutputParamsScope extends Scope
>(
    b: ContainerBuilder<ContextualScope, InputParamsScope, ContainerScope, {}, OutputParamsScope>
) {
    return b
        .addEnvVar("ETCD_ENDPOINTS", b.workflowInputs.etcdEndpoints)
        .addEnvVar("ETCD_PASSWORD", b.workflowInputs.etcdPassword)
        .addEnvVar("ETCD_USER", b.workflowInputs.etcdUser);
}

export const TargetLatchHelpers = WorkflowBuilder.create("TargetLatchHelpers")
    .addParams(CommonWorkflowParameters)
    .addTemplate("init", t=> t
        .addInputs(addCommonTargetLatchInputs)
        .addRequiredInput("targets", z.array(CLUSTER_CONFIG))
        .addRequiredInput("configuration", SNAPSHOT_MIGRATION_CONFIG)
        .addContainer(b=>b
            .addImageInfo(b.inputs.etcdUtilsImage, b.inputs.etcdUtilsImagePullPolicy)
            .addEnvVars( commonTargetLatchHelperEnvVarsFromWorkflowParameters)
            .addCommand(["sh", "-c"])
            .addArgs([initTlhScript])

            .addEnvVar("PREFIX", b.inputs.prefix)
            .addEnvVar("CONFIGURATIONS", asString(b.inputs.configuration))

            .addPathOutput("prefix", "/tmp/prefix", z.string())
            .addPathOutput("processorsPerTarget", "/tmp/processors-per-target", z.number())
        )
    )
    .addTemplate("decrementLatch", t => t
        .addInputs(addCommonTargetLatchInputs)
        .addRequiredInput("target", CLUSTER_CONFIG)
        .addRequiredInput("processor", z.string())
        .addContainer(b=>b
            .addImageInfo(b.inputs.etcdUtilsImage, b.inputs.etcdUtilsImagePullPolicy)
            .addEnvVars(commonTargetLatchHelperEnvVarsFromWorkflowParameters)
            .addCommand(["sh", "-c"])
            .addArgs([decrementTlhScript])

            .addEnvVar("PROCESSOR_ID", "")
            .addEnvVar("TARGET_ENDPOINT", "")

            .addPathOutput("shouldFinalize", "/tmp/should-finalize", z.boolean())
        )
    )
    .addTemplate("cleanup", t => t
        .addInputs(addCommonTargetLatchInputs)
        .addContainer(b=>b
            .addImageInfo(b.inputs.etcdUtilsImage, b.inputs.etcdUtilsImagePullPolicy)
            .addEnvVars(commonTargetLatchHelperEnvVarsFromWorkflowParameters)
            .addCommand(["sh", "-c"])
            .addArgs([cleanupTlhScript])

        )
    )
    .getFullScope();
