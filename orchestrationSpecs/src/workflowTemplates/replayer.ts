// TODO

import {WorkflowBuilder} from "@/schemas/workflowBuilder";
import {
    CommonWorkflowParameters, extractTargetKeysToExpressionMap, makeRequiredImageParametersForKeys,
    setupLog4jConfigForContainer, setupTestCredsForContainer, TargetClusterParameters
} from "@/workflowTemplates/commonWorkflowTemplates";
import {z} from "zod";
import {BaseExpression, expr} from "@/schemas/expression";
import {REPLAYER_OPTIONS, TARGET_CLUSTER_CONFIG} from "@/workflowTemplates/userSchemas";
import {INTERNAL} from "@/schemas/taskBuilder";
import {inputsToEnvVarsList, remapRecordNames, transformZodObjectToParams} from "@/utils";
import {IMAGE_PULL_POLICY} from "@/schemas/containerBuilder";
import {selectInputsFieldsAsExpressionRecord, selectInputsForRegister} from "@/schemas/parameterConversions";
import {typeToken} from "@/schemas/sharedTypes";

function getReplayerDeploymentManifest
(args: {
    workflowName: BaseExpression<string>,
    sessionName: BaseExpression<string>,

    loggingConfigMap: BaseExpression<string>

    podReplicas: BaseExpression<number>,
    replayerImageName: BaseExpression<string>,
    replayerImagePullPolicy: BaseExpression<IMAGE_PULL_POLICY>,

    inputsAsEnvList: Record<string, any>[]
})
{
    const baseContainerDefinition = {
        name: "replayer",
        image: args.replayerImageName,
        imagePullPolicy: args.replayerImagePullPolicy,
        env: [
            ...args.inputsAsEnvList,
            {name: "LUCENE_DIR", value: expr.literal("/tmp") }
        ]
    };
    const finalContainerDefinition =
        setupLog4jConfigForContainer(args.loggingConfigMap, baseContainerDefinition);
    return {
        apiVersion: "apps/v1",
        kind: "Deployment",
        metadata: {
            name: expr.concat(args.sessionName, expr.literal("-replayer")),
            labels: {
                app: "replayer",
                "workflows.argoproj.io/workflow": args.workflowName
            },
        },
        spec: {
            replicas: args.podReplicas,
            selector: {
                matchLabels: {
                    app: "replayer",
                },
            },
            template: {
                metadata: {
                    labels: {
                        app: "replayer",
                        "workflows.argoproj.io/workflow": args.workflowName,
                    },
                },
                spec: {
                    containers: [finalContainerDefinition]
                },
            },
        }
    }
}

export const Replayer = WorkflowBuilder.create({
    k8sResourceName: "DocumentBulkLoad",
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)


    .addTemplate("deployReplayer", t=>t // TODO: rename to createDeployment to be consistent w/ createReplicaset
        .addOptionalInput("numPods", c=>1)

        .addInputsFromRecord(TargetClusterParameters)

        .addInputsFromRecord(transformZodObjectToParams(REPLAYER_OPTIONS))
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["TrafficReplayer"]))

        .addResourceTask(b=>b
            .setDefinition({
                action: "create",
                setOwnerReference: true,
                manifest: getReplayerDeploymentManifest({
                    podReplicas: b.inputs.podReplicas,
                    loggingConfigMap: b.inputs.loggingConfigurationOverrideConfigMap,
                    sessionName: expr.literal(""),//b.inputs.sessionName,
                    replayerImageName: b.inputs.imageTrafficReplayerLocation,
                    replayerImagePullPolicy: b.inputs.imageTrafficReplayerPullPolicy,
                    inputsAsEnvList: [
                        ...inputsToEnvVarsList({...remapRecordNames(b.inputs, {"targetInsecure": "insecure"})}),
                    ],
                    workflowName: expr.getWorkflowValue("name")
                })
            }))
    )


    .addTemplate("deployReplayerFromConfig", t=>t // TODO: rename to createDeploymentFromConfig to be consistent w/ createReplicaset
        .addRequiredInput("kafkaTrafficBrokers", typeToken<string>())
        .addRequiredInput("kafkaTrafficTopic", typeToken<string>())
        .addRequiredInput("kafkaGroupId", typeToken<string>())

        .addRequiredInput("targetConfig", typeToken<z.infer<typeof TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("replayerConfig", typeToken<z.infer<typeof REPLAYER_OPTIONS>>())

        .addOptionalInput("numPods", c=>1)

        .addInputsFromRecord(makeRequiredImageParametersForKeys(["TrafficReplayer"]))

        .addSteps(b=>b
            .addStep("deployReplayer", INTERNAL, "deployReplayer", c=>
                c.register({
                    ...selectInputsForRegister(b, c),
                    ...extractTargetKeysToExpressionMap(b.inputs.targetConfig),
                    ...selectInputsFieldsAsExpressionRecord(b.inputs.replayerConfig, c)
                })))
    )


    .getFullScope();
