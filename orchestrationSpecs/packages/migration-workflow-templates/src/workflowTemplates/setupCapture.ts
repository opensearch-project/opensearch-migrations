import {
    BaseExpression,
    expr,
    INTERNAL,
    makeDirectTypeProxy,
    makeStringTypeProxy,
    Serialized,
    selectInputsForRegister,
    typeToken,
    WorkflowBuilder
} from "@opensearch-migrations/argo-workflow-builders";
import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";
import {SetupKafka} from "./setupKafka";
import {DENORMALIZED_PROXY_CONFIG} from "@opensearch-migrations/schemas";
import {makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";
import {z} from "zod";

function makeProxyServiceManifest(proxyName: BaseExpression<string>, listenPort: BaseExpression<Serialized<number>>) {
    return {
        apiVersion: "v1",
        kind: "Service",
        metadata: {
            name: proxyName,
            annotations: {
                // NLB IP mode for EKS Auto Mode — ignored on minikube/standard K8s
                "service.beta.kubernetes.io/aws-load-balancer-type": "external",
                "service.beta.kubernetes.io/aws-load-balancer-nlb-target-type": "ip"
            }
        },
        spec: {
            type: "LoadBalancer",
            selector: { "migrations/proxy": proxyName },
            ports: [{ port: makeDirectTypeProxy(listenPort),
                      targetPort: makeDirectTypeProxy(listenPort),
                      protocol: "TCP" }]
        }
    };
}

function makeProxyParamsDict(
    proxyConfig: BaseExpression<Serialized<z.infer<typeof DENORMALIZED_PROXY_CONFIG>>>
) {
    const config = expr.deserializeRecord(proxyConfig);
    return expr.mergeDicts(
        expr.omit(
            expr.get(config, "proxyConfig"),
            "podReplicas", "resources", "loggingConfigurationOverrideConfigMap"
        ),
        expr.makeDict({
            destinationUri: expr.get(config, "sourceEndpoint"),
            insecureDestination: expr.get(config, "sourceAllowInsecure"),
            kafkaConnection: expr.jsonPathStrict(proxyConfig, "kafkaConfig", "kafkaConnection"),
            kafkaTopic: expr.jsonPathStrict(proxyConfig, "kafkaConfig", "kafkaTopic"),
        })
    );
}

function makeProxyDeploymentManifest(args: {
    proxyName: BaseExpression<string>,
    image: BaseExpression<string>,
    imagePullPolicy: BaseExpression<string>,
    listenPort: BaseExpression<Serialized<number>>,
    podReplicas: BaseExpression<number>,
    jsonConfig: BaseExpression<string>,
}) {
    return {
        apiVersion: "apps/v1",
        kind: "Deployment",
        metadata: { name: args.proxyName },
        spec: {
            replicas: makeDirectTypeProxy(args.podReplicas),
            selector: { matchLabels: { "migrations/proxy": args.proxyName } },
            template: {
                metadata: { labels: { "migrations/proxy": args.proxyName } },
                spec: {
                    containers: [{
                        name: "proxy",
                        image: args.image,
                        imagePullPolicy: args.imagePullPolicy,
                        command: ["/runJavaWithClasspath.sh"],
                        args: [
                            "org.opensearch.migrations.trafficcapture.proxyserver.CaptureProxy",
                            "---INLINE-JSON",
                            makeStringTypeProxy(args.jsonConfig)
                        ],
                        ports: [{ containerPort: makeDirectTypeProxy(args.listenPort) }]
                    }]
                }
            }
        }
    };
}


export const SetupCapture = WorkflowBuilder.create({
    k8sResourceName: "setup-capture",
    parallelism: 100,
    serviceAccountName: "argo-workflow-executor",
    k8sMetadata: {}
})
    .addParams(CommonWorkflowParameters)


    .addTemplate("deployProxyService", t => t
        .addRequiredInput("proxyName",  typeToken<string>())
        .addRequiredInput("listenPort", typeToken<number>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                manifest: makeProxyServiceManifest(b.inputs.proxyName, b.inputs.listenPort)
            }))
    )


    .addTemplate("deployProxyDeployment", t => t
        .addRequiredInput("proxyName",     typeToken<string>())
        .addRequiredInput("jsonConfig",    typeToken<string>())
        .addRequiredInput("listenPort",    typeToken<number>())
        .addRequiredInput("podReplicas",   typeToken<number>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["CaptureProxy"]))
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                manifest: makeProxyDeploymentManifest({
                    proxyName:       b.inputs.proxyName,
                    image:           b.inputs.imageCaptureProxyLocation,
                    imagePullPolicy: b.inputs.imageCaptureProxyPullPolicy,
                    listenPort:      b.inputs.listenPort,
                    podReplicas:     expr.deserializeRecord(b.inputs.podReplicas),
                    jsonConfig:      expr.toBase64(b.inputs.jsonConfig),
                })
            }))
    )


    .addTemplate("setupProxy", t => t
        .addRequiredInput("proxyConfig",       typeToken<z.infer<typeof DENORMALIZED_PROXY_CONFIG>>())
        .addRequiredInput("kafkaClusterName",  typeToken<string>())
        .addRequiredInput("kafkaTopicName",    typeToken<string>())
        .addRequiredInput("proxyName",         typeToken<string>())
        .addRequiredInput("listenPort",        typeToken<number>())
        .addRequiredInput("podReplicas",       typeToken<number>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["CaptureProxy"]))

        .addSteps(b => b
            .addStep("createKafkaTopic", SetupKafka, "createKafkaTopic", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    clusterName:   b.inputs.kafkaClusterName,
                    topicName:     b.inputs.kafkaTopicName,
                    clusterConfig: expr.serialize(expr.literal({})),
                })
            )
            .addStep("deployService", INTERNAL, "deployProxyService", c =>
                c.register({
                    proxyName:  b.inputs.proxyName,
                    listenPort: b.inputs.listenPort,
                })
            )
            .addStep("deployProxy", INTERNAL, "deployProxyDeployment", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    proxyName:    expr.get(expr.deserializeRecord(b.inputs.proxyConfig), "name"),
                    listenPort:   b.inputs.listenPort,
                    podReplicas:  b.inputs.podReplicas,
                    jsonConfig:   expr.asString(expr.serialize(
                        makeProxyParamsDict(b.inputs.proxyConfig) as any
                    )),
                })
            )
        )
    )


    .getFullScope();
