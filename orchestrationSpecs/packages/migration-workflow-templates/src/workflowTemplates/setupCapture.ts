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
import {DENORMALIZED_PROXY_CONFIG, PROXY_TLS_CONFIG} from "@opensearch-migrations/schemas";
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
    tlsSecretName?: BaseExpression<string>,
}) {
    const baseArgs = [
        "org.opensearch.migrations.trafficcapture.proxyserver.CaptureProxy",
        "---INLINE-JSON",
        makeStringTypeProxy(args.jsonConfig)
    ];
    const containerArgs = args.tlsSecretName
        ? [...baseArgs, "--sslCertChainFile", "/etc/proxy-tls/tls.crt", "--sslKeyFile", "/etc/proxy-tls/tls.key"]
        : baseArgs;

    const container: Record<string, any> = {
        name: "proxy",
        image: args.image,
        imagePullPolicy: args.imagePullPolicy,
        command: ["/runJavaWithClasspath.sh"],
        args: containerArgs,
        ports: [{ containerPort: makeDirectTypeProxy(args.listenPort) }]
    };
    if (args.tlsSecretName) {
        container.volumeMounts = [{ name: "tls-certs", mountPath: "/etc/proxy-tls", readOnly: true }];
    }

    const podSpec: Record<string, any> = { containers: [container] };
    if (args.tlsSecretName) {
        podSpec.volumes = [{ name: "tls-certs", secret: { secretName: args.tlsSecretName } }];
    }

    return {
        apiVersion: "apps/v1",
        kind: "Deployment",
        metadata: { name: args.proxyName },
        spec: {
            replicas: makeDirectTypeProxy(args.podReplicas),
            selector: { matchLabels: { "migrations/proxy": args.proxyName } },
            template: {
                metadata: { labels: { "migrations/proxy": args.proxyName } },
                spec: podSpec
            }
        }
    };
}

function makeCertificateManifest(args: {
    certName: BaseExpression<string>,
    secretName: BaseExpression<string>,
    issuerName: BaseExpression<string>,
    issuerKind: BaseExpression<string>,
    issuerGroup: BaseExpression<string>,
    dnsNames: BaseExpression<string[]>,
    duration: BaseExpression<string>,
    renewBefore: BaseExpression<string>,
}) {
    return {
        apiVersion: "cert-manager.io/v1",
        kind: "Certificate",
        metadata: { name: args.certName },
        spec: {
            secretName: args.secretName,
            issuerRef: {
                name: args.issuerName,
                kind: args.issuerKind,
                group: args.issuerGroup,
            },
            dnsNames: args.dnsNames,
            duration: args.duration,
            renewBefore: args.renewBefore,
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
        .addOptionalInput("tlsSecretName", c => "")
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


    .addTemplate("deployProxyDeploymentWithTls", t => t
        .addRequiredInput("proxyName",       typeToken<string>())
        .addRequiredInput("jsonConfig",      typeToken<string>())
        .addRequiredInput("listenPort",      typeToken<number>())
        .addRequiredInput("podReplicas",     typeToken<number>())
        .addRequiredInput("tlsSecretName",   typeToken<string>())
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
                    tlsSecretName:   b.inputs.tlsSecretName,
                })
            }))
    )


    .addTemplate("provisionProxyCert", t => t
        .addRequiredInput("certName",     typeToken<string>())
        .addRequiredInput("secretName",   typeToken<string>())
        .addRequiredInput("issuerName",   typeToken<string>())
        .addRequiredInput("issuerKind",   typeToken<string>())
        .addRequiredInput("issuerGroup",  typeToken<string>())
        .addRequiredInput("dnsNames",     typeToken<string[]>())
        .addRequiredInput("duration",     typeToken<string>())
        .addRequiredInput("renewBefore",  typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                manifest: makeCertificateManifest({
                    certName:     b.inputs.certName,
                    secretName:   b.inputs.secretName,
                    issuerName:   b.inputs.issuerName,
                    issuerKind:   b.inputs.issuerKind,
                    issuerGroup:  b.inputs.issuerGroup,
                    dnsNames:     expr.deserializeRecord(b.inputs.dnsNames),
                    duration:     b.inputs.duration,
                    renewBefore:  b.inputs.renewBefore,
                })
            }))
    )


    .addTemplate("waitForCertReady", t => t
        .addRequiredInput("certName", typeToken<string>())
        .addWaitForExistingResource(b => b
            .setDefinition({
                resource: {
                    apiVersion: "cert-manager.io/v1",
                    kind: "Certificate",
                    name: b.inputs.certName,
                },
                conditions: {
                    successCondition: "status.conditions.0.status == True",
                    failureCondition: "status.conditions.0.status == False",
                }
            }))
    )


    .addTemplate("setupProxy", t => t
        .addRequiredInput("proxyConfig",       typeToken<z.infer<typeof DENORMALIZED_PROXY_CONFIG>>())
        .addRequiredInput("kafkaClusterName",  typeToken<string>())
        .addRequiredInput("kafkaTopicName",    typeToken<string>())
        .addRequiredInput("proxyName",         typeToken<string>())
        .addRequiredInput("listenPort",        typeToken<number>())
        .addRequiredInput("podReplicas",       typeToken<number>())
        .addOptionalInput("tlsSecretName",     c =>"")
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
            .addStepGroup(g => g
                .addStep("deployProxyNoTls", INTERNAL, "deployProxyDeployment", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        proxyName:    expr.get(expr.deserializeRecord(b.inputs.proxyConfig), "name"),
                        listenPort:   b.inputs.listenPort,
                        podReplicas:  b.inputs.podReplicas,
                        jsonConfig:   expr.asString(expr.serialize(
                            makeProxyParamsDict(b.inputs.proxyConfig) as any
                        )),
                    }),
                    { when: { templateExp: expr.isEmpty(b.inputs.tlsSecretName) } }
                )
                .addStep("deployProxyWithTls", INTERNAL, "deployProxyDeploymentWithTls", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        proxyName:      expr.get(expr.deserializeRecord(b.inputs.proxyConfig), "name"),
                        listenPort:     b.inputs.listenPort,
                        podReplicas:    b.inputs.podReplicas,
                        tlsSecretName:  b.inputs.tlsSecretName,
                        jsonConfig:     expr.asString(expr.serialize(
                            makeProxyParamsDict(b.inputs.proxyConfig) as any
                        )),
                    }),
                    { when: { templateExp: expr.not(expr.isEmpty(b.inputs.tlsSecretName)) } }
                )
            )
        )
    )


    .getFullScope();
