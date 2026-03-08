
import {z} from "zod";
import {
    NAMED_TARGET_CLUSTER_CONFIG,
    REPLAYER_OPTIONS,
    ResourceRequirementsType,
} from "@opensearch-migrations/schemas";
import {
    BaseExpression,
    expr,
    IMAGE_PULL_POLICY,
    INTERNAL, makeDirectTypeProxy, makeStringTypeProxy,
    selectInputsForRegister, Serialized,
    typeToken,
    WorkflowBuilder
} from "@opensearch-migrations/argo-workflow-builders";
import {setupLog4jConfigForContainer} from "./commonUtils/containerFragments";
import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";
import {makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";

function makeReplayerTargetParamDict(
    targetConfig: BaseExpression<Serialized<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>>
) {
    const tc = expr.deserializeRecord(targetConfig);
    const safeAuth = expr.getLoose(tc, "authConfig");
    return expr.mergeDicts(
        expr.makeDict({
            targetUri: expr.get(tc, "endpoint"),
            insecure: expr.dig(expr.deserializeRecord(targetConfig), ["allowInsecure"], false),
        }),
        expr.ternary(
            expr.hasKey(tc, "authConfig"),
            expr.ternary(
                expr.hasKey(safeAuth, "sigv4"),
                expr.makeDict({
                    sigv4AuthHeaderServiceRegion: expr.concat(
                        expr.getLoose(expr.getLoose(safeAuth, "sigv4"), "service"),
                        expr.literal(","),
                        expr.getLoose(expr.getLoose(safeAuth, "sigv4"), "region")
                    )
                }),
                expr.literal({})
            ),
            expr.literal({})
        )
    );
}

function makeParamsDict(
    targetConfig: BaseExpression<Serialized<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>>,
    options: BaseExpression<Serialized<z.infer<typeof REPLAYER_OPTIONS>>>,
    kafkaBrokers: BaseExpression<string>,
    kafkaTopic: BaseExpression<string>,
    kafkaGroupId: BaseExpression<string>,
) {
    return expr.mergeDicts(
        expr.mergeDicts(
            makeReplayerTargetParamDict(targetConfig),
            expr.omit(expr.deserializeRecord(options), "loggingConfigurationOverrideConfigMap", "podReplicas", "resources", "jvmArgs")
        ),
        expr.makeDict({
            kafkaTrafficBrokers: kafkaBrokers,
            kafkaTrafficTopic: kafkaTopic,
            kafkaTrafficGroupId: kafkaGroupId,
        })
    );
}


function getReplayerDeploymentManifest
(args: {
    workflowName: BaseExpression<string>,
    jsonConfig: BaseExpression<string>,
    sessionName: BaseExpression<string>,

    useCustomLogging: BaseExpression<boolean>,
    loggingConfigMap: BaseExpression<string>,
    jvmArgs: BaseExpression<string>,

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
            {container: baseContainerDefinition, volumes: []},
            args.jvmArgs);
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
        .addRequiredInput("jvmArgs", typeToken<string>())
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
                    jvmArgs: b.inputs.jvmArgs,
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
        .addRequiredInput("kafkaTopicName", typeToken<string>())
        .addRequiredInput("kafkaGroupId", typeToken<string>())

        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("replayerConfig", typeToken<z.infer<typeof REPLAYER_OPTIONS>>())

        .addOptionalInput("podReplicas", c => 1)

        .addInputsFromRecord(makeRequiredImageParametersForKeys(["TrafficReplayer"]))

        .addSteps(b => b
            .addStep("deployReplayer", INTERNAL, "createDeployment", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    sessionName: "",
                    podReplicas: expr.dig(expr.deserializeRecord(b.inputs.replayerConfig), ["podReplicas"], 1),
                    jvmArgs: expr.dig(expr.deserializeRecord(b.inputs.replayerConfig), ["jvmArgs"], ""),
                    loggingConfigurationOverrideConfigMap: expr.dig(expr.deserializeRecord(b.inputs.replayerConfig), ["loggingConfigurationOverrideConfigMap"], ""),
                    jsonConfig: expr.asString(expr.serialize(
                        makeParamsDict(
                            b.inputs.targetConfig,
                            b.inputs.replayerConfig,
                            b.inputs.kafkaTrafficBrokers,
                            b.inputs.kafkaTopicName,
                            b.inputs.kafkaGroupId,
                        ) as any
                    )),
                    resources: expr.serialize(expr.getLoose(expr.deserializeRecord(b.inputs.replayerConfig), "resources"))
                })))
    )


    .getFullScope();
