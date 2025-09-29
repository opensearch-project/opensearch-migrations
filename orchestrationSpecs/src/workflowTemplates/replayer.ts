import {WorkflowBuilder} from "@/argoWorkflowBuilders/models/workflowBuilder";
import {
    CommonWorkflowParameters,
    extractTargetKeysToExpressionMap,
    makeRequiredImageParametersForKeys,
    setupLog4jConfigForContainer,
    TargetClusterParameters
} from "@/workflowTemplates/commonWorkflowTemplates";
import {z} from "zod";
import {BaseExpression, expr} from "@/argoWorkflowBuilders/models/expression";
import {REPLAYER_OPTIONS, TARGET_CLUSTER_CONFIG} from "@/workflowTemplates/userSchemas";
import {INTERNAL} from "@/argoWorkflowBuilders/models/taskBuilder";
import {inputsToEnvVarsList, remapRecordNames, transformZodObjectToParams} from "@/utils";
import {IMAGE_PULL_POLICY} from "@/argoWorkflowBuilders/models/containerBuilder";
import {selectInputsFieldsAsExpressionRecord, selectInputsForRegister} from "@/argoWorkflowBuilders/models/parameterConversions";
import {typeToken} from "@/argoWorkflowBuilders/models/sharedTypes";

function getReplayerDeploymentManifest
(args: {
    workflowName: BaseExpression<string>,
    sessionName: BaseExpression<string>,

    useCustomLogging: BaseExpression<boolean>,
    loggingConfigMap: BaseExpression<string>,

    podReplicas: BaseExpression<number>,
    replayerImageName: BaseExpression<string>,
    replayerImagePullPolicy: BaseExpression<IMAGE_PULL_POLICY>,

    inputsAsEnvList: Record<string, any>[]
}) {
    const baseContainerDefinition = {
        name: "replayer",
        image: args.replayerImageName,
        imagePullPolicy: args.replayerImagePullPolicy,
        env: [
            ...args.inputsAsEnvList,
            {name: "LUCENE_DIR", value: expr.literal("/tmp")}
        ]
    };
    const finalContainerDefinition =
        setupLog4jConfigForContainer(args.useCustomLogging, args.loggingConfigMap,
            {container: baseContainerDefinition, volumes: []});
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
                    containers: [finalContainerDefinition.container],
                    volumes: finalContainerDefinition.volumes
                },
            },
        }
    }
}


export const Replayer = WorkflowBuilder.create({
    k8sResourceName: "replayer",
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)


    .addTemplate("deployReplayer", t => t // TODO: rename to createDeployment to be consistent w/ createReplicaset
        .addOptionalInput("numPods", c => 1)

        .addInputsFromRecord(TargetClusterParameters)

        .addInputsFromRecord(transformZodObjectToParams(REPLAYER_OPTIONS))
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["TrafficReplayer"]))

        .addResourceTask(b => b
            .setDefinition({
                action: "create",
                setOwnerReference: true,
                manifest: getReplayerDeploymentManifest({
                    podReplicas: b.inputs.podReplicas,
                    useCustomLogging:
                        expr.equals(expr.literal(""),
                            expr.nullCoalesce(b.inputs.loggingConfigurationOverrideConfigMap, expr.literal(""))),
                    loggingConfigMap: b.inputs.loggingConfigurationOverrideConfigMap,
                    sessionName: expr.literal(""),//b.inputs.sessionName,
                    replayerImageName: b.inputs.imageTrafficReplayerLocation,
                    replayerImagePullPolicy: b.inputs.imageTrafficReplayerPullPolicy,
                    inputsAsEnvList: [
                        ...inputsToEnvVarsList({...remapRecordNames(b.inputs, {"targetInsecure": "insecure"})},
                            "REPLAYER_", "_CMD_LINE_ARG"),
                    ],
                    workflowName: expr.getWorkflowValue("name")
                })
            }))
    )


    .addTemplate("deployReplayerFromConfig", t => t // TODO: rename to createDeploymentFromConfig to be consistent w/ createReplicaset
        .addRequiredInput("kafkaTrafficBrokers", typeToken<string>())
        .addRequiredInput("kafkaTrafficTopic", typeToken<string>())
        .addRequiredInput("kafkaGroupId", typeToken<string>())

        .addRequiredInput("targetConfig", typeToken<z.infer<typeof TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("replayerConfig", typeToken<z.infer<typeof REPLAYER_OPTIONS>>())

        .addOptionalInput("numPods", c => 1)

        .addInputsFromRecord(makeRequiredImageParametersForKeys(["TrafficReplayer"]))

        .addSteps(b => b
            .addStep("deployReplayer", INTERNAL, "deployReplayer", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    ...extractTargetKeysToExpressionMap(b.inputs.targetConfig),
                    ...selectInputsFieldsAsExpressionRecord(b.inputs.replayerConfig, c)
                })))
    )


    .getFullScope();
