import {z} from 'zod';
import {
    NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG,
    PARAMETERIZED_MIGRATION_CONFIG,
    SOURCE_CLUSTERS_MAP,
    TARGET_CLUSTERS_MAP
} from '@opensearch-migrations/schemas'
import {
    CommonWorkflowParameters,
    makeRequiredImageParametersForKeys
} from "./commonWorkflowTemplates";
import {TemplateBuilder, typeToken, WorkflowBuilder} from "@opensearch-migrations/argo-workflow-builders";

import {initTlhScript} from "../resourceLoader";
import {decrementTlhScript} from "../resourceLoader";
import {cleanupTlhScript} from "../resourceLoader";

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
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["EtcdUtils"]));
}

export const ConfigManagementHelpers = WorkflowBuilder.create({
    k8sResourceName: "target-latch-helpers",
    serviceAccountName: "argo-workflow-executor",
    k8sMetadata: {},
    parallelism: 1
})
    .addParams(CommonWorkflowParameters)


    .addTemplate("decrementLatch", t => t
        .addInputs(addCommonTargetLatchInputs)
        .addRequiredInput("targetName", typeToken<string>())
        .addRequiredInput("processorId", typeToken<string>())
        .addContainer(b => b
            .addImageInfo(b.inputs.imageEtcdUtilsLocation, b.inputs.imageEtcdUtilsPullPolicy)
            .addInputsAsEnvVars({prefix:"", suffix: ""})
            .addCommand(["sh", "-c"])
            .addArgs([decrementTlhScript])

            .addPathOutput("shouldFinalize", "/tmp/should-finalize", typeToken<boolean>())
        )
    )


    .addTemplate("cleanup", t => t
        .addInputs(addCommonTargetLatchInputs)
        .addContainer(b => b
            .addImageInfo(b.inputs.imageEtcdUtilsLocation, b.inputs.imageEtcdUtilsPullPolicy)
            .addInputsAsEnvVars({prefix: "", suffix: ""})
            .addCommand(["sh", "-c"])
            .addArgs([cleanupTlhScript])
        )
    )
    .getFullScope();
