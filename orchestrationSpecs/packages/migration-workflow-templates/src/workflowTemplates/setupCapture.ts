import {
    BaseExpression,
    expr,
    INTERNAL,
    makeDirectTypeProxy,
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

function makeProxyDeploymentManifest(args: {
    proxyName: BaseExpression<string>,
    image: BaseExpression<string>,
    imagePullPolicy: BaseExpression<string>,
    sourceEndpoint: BaseExpression<string>,
    kafkaConnection: BaseExpression<string>,
    kafkaTopic: BaseExpression<string>,
    listenPort: BaseExpression<Serialized<number>>,
    podReplicas: BaseExpression<number>,
    otelCollectorEndpoint: BaseExpression<string>,
    allowInsecure: BaseExpression<boolean>,
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
                        env: [
                            { name: "BACKSIDE_URI_STRING",  value: args.sourceEndpoint },
                            { name: "FRONTSIDE_PORT",       value: args.listenPort },
                            { name: "KAFKA_CONNECTION",     value: args.kafkaConnection },
                            { name: "KAFKA_TOPIC",          value: args.kafkaTopic },
                            { name: "OTEL_COLLECTOR_ENDPOINT", value: args.otelCollectorEndpoint },
                            { name: "ALLOW_INSECURE_CONNECTIONS_TO_BACKSIDE", value: args.allowInsecure },
                            // TODO: MSK auth, SSL config, header overrides
                        ],
                        command: ["/bin/sh", "-c", [
                            "set -e",
                            "ARGS=\"\"",
                            "ARGS=\"${ARGS}${BACKSIDE_URI_STRING:+ --destinationUri $BACKSIDE_URI_STRING}\"",
                            "ARGS=\"${ARGS}${FRONTSIDE_PORT:+ --listenPort $FRONTSIDE_PORT}\"",
                            "ARGS=\"${ARGS}${KAFKA_CONNECTION:+ --kafkaConnection $KAFKA_CONNECTION}\"",
                            "ARGS=\"${ARGS}${KAFKA_TOPIC:+ --kafkaTopic $KAFKA_TOPIC}\"",
                            "ARGS=\"${ARGS}${OTEL_COLLECTOR_ENDPOINT:+ --otelCollectorEndpoint $OTEL_COLLECTOR_ENDPOINT}\"",
                            "if [ \"$ALLOW_INSECURE_CONNECTIONS_TO_BACKSIDE\" = \"true\" ]; then ARGS=\"${ARGS} --insecureDestination\"; fi",
                            "echo \"Starting proxy with: $ARGS\"",
                            "exec /runJavaWithClasspath.sh org.opensearch.migrations.trafficcapture.proxyserver.CaptureProxy $ARGS"
                        ].join("\n")],
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


    // Define leaf templates first so setupProxy can reference them via INTERNAL

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
        .addRequiredInput("proxyConfig",   typeToken<z.infer<typeof DENORMALIZED_PROXY_CONFIG>>())
        .addRequiredInput("listenPort",    typeToken<number>())
        .addRequiredInput("podReplicas",   typeToken<number>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["CaptureProxy"]))
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                manifest: makeProxyDeploymentManifest({
                    proxyName:             expr.get(expr.deserializeRecord(b.inputs.proxyConfig), "name"),
                    image:                 b.inputs.imageCaptureProxyLocation,
                    imagePullPolicy:       b.inputs.imageCaptureProxyPullPolicy,
                    sourceEndpoint:        expr.jsonPathStrict(b.inputs.proxyConfig, "sourceEndpoint"),
                    kafkaConnection:       expr.jsonPathStrict(b.inputs.proxyConfig, "kafkaConfig", "kafkaConnection"),
                    kafkaTopic:            expr.jsonPathStrict(b.inputs.proxyConfig, "kafkaConfig", "kafkaTopic"),
                    listenPort:            b.inputs.listenPort,
                    podReplicas:           expr.deserializeRecord(b.inputs.podReplicas),
                    otelCollectorEndpoint: expr.cast(expr.jsonPathStrict(b.inputs.proxyConfig, "proxyConfig", "otelCollectorEndpoint")).to<string>(),
                    allowInsecure:         expr.cast(expr.jsonPathStrict(b.inputs.proxyConfig, "proxyConfig", "noCapture")).to<boolean>(),
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
                    proxyConfig:  b.inputs.proxyConfig,
                    listenPort:   b.inputs.listenPort,
                    podReplicas:  b.inputs.podReplicas,
                })
            )
        )
    )


    .getFullScope();
