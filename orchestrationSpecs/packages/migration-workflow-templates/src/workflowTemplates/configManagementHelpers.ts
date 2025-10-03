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
} from "@/workflowTemplates/commonWorkflowTemplates";
import {TemplateBuilder, typeToken, WorkflowBuilder} from "@opensearch-migrations/argo-workflow-builders";

import initTlhScript from "resources/configManagementHelpers/init.sh";
import decrementTlhScript from "resources/configManagementHelpers/decrement.sh";
import cleanupTlhScript from "resources/configManagementHelpers/cleanup.sh";

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
    .addTemplate("prepareConfigs", t => t
        .addInputs(addCommonTargetLatchInputs)
        .addRequiredInput("targetClusters", typeToken<z.infer<typeof TARGET_CLUSTERS_MAP>>())
        .addRequiredInput("sourceClusters", typeToken<z.infer<typeof SOURCE_CLUSTERS_MAP>>())
        .addRequiredInput("sourceMigrationConfigs", typeToken<z.infer<typeof NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG>[]>())

        .addContainer(b => b
            .addImageInfo(b.inputs.imageEtcdUtilsLocation, b.inputs.imageEtcdUtilsPullPolicy)
            .addInputsAsEnvVars("WF_SETUP_", "")
            .addCommand(["sh", "-c"])
            .addArgs([initTlhScript])

            .addPathOutput("prefix", "/tmp/prefix", typeToken<string>())
            .addPathOutput("denormalizedConfigArray", "/tmp/denormalizedConfigs", typeToken<z.infer<typeof PARAMETERIZED_MIGRATION_CONFIG>[]>())
        )
    )


    .addTemplate("decrementLatch", t => t
        .addInputs(addCommonTargetLatchInputs)
        .addRequiredInput("targetName", typeToken<string>())
        .addRequiredInput("processorId", typeToken<string>())
        .addContainer(b => b
            .addImageInfo(b.inputs.imageEtcdUtilsLocation, b.inputs.imageEtcdUtilsPullPolicy)
            .addInputsAsEnvVars("", "")
            .addCommand(["sh", "-c"])
            .addArgs([decrementTlhScript])

            .addPathOutput("shouldFinalize", "/tmp/should-finalize", typeToken<boolean>())
        )
    )


    .addTemplate("cleanup", t => t
        .addInputs(addCommonTargetLatchInputs)
        .addContainer(b => b
            .addImageInfo(b.inputs.imageEtcdUtilsLocation, b.inputs.imageEtcdUtilsPullPolicy)
            .addInputsAsEnvVars("", "")
            .addCommand(["sh", "-c"])
            .addArgs([cleanupTlhScript])
        )
    )
    .getFullScope();
