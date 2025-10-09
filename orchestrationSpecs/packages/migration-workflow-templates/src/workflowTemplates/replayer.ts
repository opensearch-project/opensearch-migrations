import {
    CommonWorkflowParameters,
    extractTargetKeysToExpressionMap,
    makeRequiredImageParametersForKeys,
    setupLog4jConfigForContainer,
    TargetClusterParameters
} from "./commonWorkflowTemplates";
import {z} from "zod";
import {getZodKeys, REPLAYER_OPTIONS, TARGET_CLUSTER_CONFIG} from "@opensearch-migrations/schemas";
import {
    BaseExpression,
    expr,
    IMAGE_PULL_POLICY,
    inputsToEnvVarsList,
    INTERNAL,
    remapRecordNames,
    selectInputsFieldsAsExpressionRecord,
    selectInputsForRegister,
    transformZodObjectToParams,
    typeToken,
    WorkflowBuilder
} from "@opensearch-migrations/argo-workflow-builders";

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
        .addInputsFromRecord(TargetClusterParameters)
        .addInputsFromRecord(transformZodObjectToParams(REPLAYER_OPTIONS))
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["TrafficReplayer"]))

        .addResourceTask(b => b
            .setDefinition({
                action: "create",
                setOwnerReference: true,
                manifest: getReplayerDeploymentManifest({
                    podReplicas: expr.literal(0),//b.inputs.podReplicas,
                    useCustomLogging:
                        expr.equals(expr.literal(""),
                            expr.nullCoalesce(b.inputs.loggingConfigurationOverrideConfigMap, expr.literal(""))),
                    loggingConfigMap: b.inputs.loggingConfigurationOverrideConfigMap,
                    sessionName: expr.literal(""),//b.inputs.sessionName,
                    replayerImageName: b.inputs.imageTrafficReplayerLocation,
                    replayerImagePullPolicy: b.inputs.imageTrafficReplayerPullPolicy,
                    inputsAsEnvList: inputsToEnvVarsList(remapRecordNames(b.inputs, {"targetInsecure": "insecure"}),
                            "JCOMMANDER"),
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

        .addOptionalInput("podReplicas", c => 1)

        .addInputsFromRecord(makeRequiredImageParametersForKeys(["TrafficReplayer"]))

        .addSteps(b => b
            .addStep("deployReplayer", INTERNAL, "deployReplayer", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    ...extractTargetKeysToExpressionMap(b.inputs.targetConfig),
                    ...selectInputsFieldsAsExpressionRecord(expr.deserializeRecord(b.inputs.replayerConfig), c,
                        getZodKeys(REPLAYER_OPTIONS))
                })))
    )


    .getFullScope();

