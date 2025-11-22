
import {z} from "zod";
import {
    getZodKeys,
    NAMED_TARGET_CLUSTER_CONFIG,
    REPLAYER_OPTIONS,
    ResourceRequirementsType,
} from "@opensearch-migrations/schemas";
import {
    BaseExpression,
    expr,
    IMAGE_PULL_POLICY,
    INTERNAL, makeDirectTypeProxy, makeStringTypeProxy,
    selectInputsFieldsAsExpressionRecord,
    selectInputsForRegister, Serialized,
    typeToken,
    WorkflowBuilder
} from "@opensearch-migrations/argo-workflow-builders";
import {setupLog4jConfigForContainer} from "./commonUtils/containerFragments";
import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";
import {makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";
import {extractTargetKeysToExpressionMap, makeTargetParamDict} from "./commonUtils/clusterSettingManipulators";

function makeParamsDict(
    targetConfig: BaseExpression<Serialized<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>>,
    options: BaseExpression<Serialized<z.infer<typeof REPLAYER_OPTIONS>>>,
    // kafka stuff will need to come in here too
) {
    return expr.mergeDicts(
        expr.mergeDicts(
            makeTargetParamDict(targetConfig),
            expr.omit(expr.deserializeRecord(options), "loggingConfigurationOverrideConfigMap", "podReplicas", "resources")
        ),
        expr.mergeDicts(
            expr.makeDict({}),
            expr.makeDict({})
        )
    );
}


function getReplayerDeploymentManifest
(args: {
    workflowName: BaseExpression<string>,
    jsonConfig: BaseExpression<string>,
    sessionName: BaseExpression<string>,

    useCustomLogging: BaseExpression<boolean>,
    loggingConfigMap: BaseExpression<string>,

    podReplicas: BaseExpression<number>,
    replayerImageName: BaseExpression<string>,
    replayerImagePullPolicy: BaseExpression<IMAGE_PULL_POLICY>,
    resources: BaseExpression<ResourceRequirementsType>
}) {
    const baseContainerDefinition = {
        name: "replayer",
        image: makeStringTypeProxy(args.replayerImageName),
        imagePullPolicy: makeStringTypeProxy(args.replayerImagePullPolicy),
        command: ["/runJavaWithClasspath.sh"],
        args: [
            "org.opensearch.migrations.replay.TrafficReplayer",
            "---INLINE-JSON",
            makeStringTypeProxy(args.jsonConfig)
        ],
        resources: makeDirectTypeProxy(args.resources)
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
                    serviceAccountName: "argo-workflow-executor",
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


    .addTemplate("createDeployment", t => t
        .addRequiredInput("sessionName", typeToken<string>())
        .addRequiredInput("jsonConfig", typeToken<string>())
        .addRequiredInput("podReplicas", typeToken<number>())
        .addRequiredInput("loggingConfigurationOverrideConfigMap", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["TrafficReplayer"]))
        .addRequiredInput("resources", typeToken<ResourceRequirementsType>())

        .addResourceTask(b => b
            .setDefinition({
                action: "create",
                setOwnerReference: true,
                manifest: getReplayerDeploymentManifest({
                    podReplicas: expr.deserializeRecord(b.inputs.podReplicas),
                    useCustomLogging: expr.equals(expr.literal(""), b.inputs.loggingConfigurationOverrideConfigMap),
                    loggingConfigMap: b.inputs.loggingConfigurationOverrideConfigMap,
                    sessionName: b.inputs.sessionName,
                    replayerImageName: b.inputs.imageTrafficReplayerLocation,
                    replayerImagePullPolicy: b.inputs.imageTrafficReplayerPullPolicy,
                    workflowName: expr.getWorkflowValue("name"),
                    jsonConfig: expr.toBase64(b.inputs.jsonConfig),
                    resources: expr.deserializeRecord(b.inputs.resources),
                })
            }))
    )


    .addTemplate("createDeploymentFromConfig", t => t
        .addRequiredInput("kafkaTrafficBrokers", typeToken<string>())
        .addRequiredInput("kafkaTrafficTopic", typeToken<string>())
        .addRequiredInput("kafkaGroupId", typeToken<string>())

        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("replayerConfig", typeToken<z.infer<typeof REPLAYER_OPTIONS>>())

        .addOptionalInput("podReplicas", c => 1)

        .addInputsFromRecord(makeRequiredImageParametersForKeys(["TrafficReplayer"]))

        .addSteps(b => b
            .addStep("deployReplayer", INTERNAL, "createDeployment", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    ...extractTargetKeysToExpressionMap(b.inputs.targetConfig),
                    ...selectInputsFieldsAsExpressionRecord(expr.deserializeRecord(b.inputs.replayerConfig), c,
                        getZodKeys(REPLAYER_OPTIONS)),
                    sessionName: "",
                    jsonConfig: expr.asString(expr.serialize(
                        makeParamsDict(b.inputs.targetConfig, b.inputs.replayerConfig)
                    )),
                    resources: expr.serialize(expr.getLoose(expr.deserializeRecord(b.inputs.replayerConfig), "resources"))
                })))
    )


    .getFullScope();
