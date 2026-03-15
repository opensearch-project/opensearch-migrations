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
import {
    ARGO_PROXY_WORKFLOW_OPTION_KEYS,
    DENORMALIZED_PROXY_CONFIG,
    PROXY_TLS_CONFIG,
    ResourceRequirementsType,
} from "@opensearch-migrations/schemas";
import {makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";
import {z} from "zod";
import {K8S_RESOURCE_RETRY_STRATEGY} from "./commonUtils/resourceRetryStrategy";

function makeProxyServiceManifest(
    proxyName: BaseExpression<string>,
    listenPort: BaseExpression<Serialized<number>>,
    internetFacing: BaseExpression<boolean>
) {
    return {
        apiVersion: "v1",
        kind: "Service",
        metadata: {
            name: proxyName,
            annotations: {
                // NLB IP mode for EKS Auto Mode — ignored on minikube/standard K8s
                "service.beta.kubernetes.io/aws-load-balancer-type": "external",
                "service.beta.kubernetes.io/aws-load-balancer-nlb-target-type": "ip",
                "service.beta.kubernetes.io/aws-load-balancer-scheme": makeStringTypeProxy(
                    expr.ternary(internetFacing, expr.literal("internet-facing"), expr.literal("internal"))
                )
            }
        },
        spec: {
            type: "LoadBalancer",
            selector: {"migrations/proxy": proxyName},
            ports: [{
                port: makeDirectTypeProxy(listenPort),
                targetPort: makeDirectTypeProxy(listenPort),
                protocol: "TCP"
            }]
        }
    };
}

function makeProxyParamsDict(
    proxyConfig: BaseExpression<Serialized<z.infer<typeof DENORMALIZED_PROXY_CONFIG>>>
) {
    const config = expr.deserializeRecord(proxyConfig);
    return expr.mergeDicts(
        expr.omit(expr.get(config, "proxyConfig"), ...ARGO_PROXY_WORKFLOW_OPTION_KEYS),
        expr.mergeDicts(
            expr.ternary(expr.dig(config, ["proxyConfig", "noCapture"], false),
                expr.literal({}),
                expr.makeDict({
                    kafkaConnection: expr.jsonPathStrict(proxyConfig, "kafkaConfig", "kafkaConnection")
                })
            ),
            expr.makeDict({
                destinationUri: expr.get(config, "sourceEndpoint"),
                insecureDestination: expr.get(config, "sourceAllowInsecure"),
                kafkaTopic: expr.jsonPathStrict(proxyConfig, "kafkaConfig", "kafkaTopic"),

            })
        )
    );
}

function makeProxyDeploymentManifest(args: {
    proxyName: BaseExpression<string>,
    image: BaseExpression<string>,
    imagePullPolicy: BaseExpression<string>,
    listenPort: BaseExpression<Serialized<number>>,
    podReplicas: BaseExpression<number>,
    resources: BaseExpression<ResourceRequirementsType>,
    jsonConfig: BaseExpression<string>,
    tlsSecretName?: BaseExpression<string>,
}) {
    const container: Record<string, any> = {
        name: "proxy",
        image: args.image,
        imagePullPolicy: args.imagePullPolicy,
        args: [
            "---INLINE-JSON",
            makeStringTypeProxy(args.jsonConfig)
        ],
        ports: [{containerPort: makeDirectTypeProxy(args.listenPort)}],
        resources: makeDirectTypeProxy(args.resources)
    };
    if (args.tlsSecretName) {
        container.volumeMounts = [{name: "tls-certs", mountPath: "/etc/proxy-tls", readOnly: true}];
    }

    const podSpec: Record<string, any> = {containers: [container]};
    if (args.tlsSecretName) {
        podSpec.volumes = [{name: "tls-certs", secret: {secretName: args.tlsSecretName}}];
    }

    return {
        apiVersion: "apps/v1",
        kind: "Deployment",
        metadata: {name: args.proxyName},
        spec: {
            replicas: makeDirectTypeProxy(args.podReplicas),
            selector: {matchLabels: {"migrations/proxy": args.proxyName}},
            template: {
                metadata: {labels: {"migrations/proxy": args.proxyName}},
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
    dnsNames: BaseExpression<string>,
    duration: BaseExpression<string>,
    renewBefore: BaseExpression<string>,
}) {
    // dnsNames must be rendered as a raw JSON array in the manifest, not quoted.
    // We use makeDirectTypeProxy to prevent quoting so the JSON array is inlined directly.
    return {
        apiVersion: "cert-manager.io/v1",
        kind: "Certificate",
        metadata: {name: args.certName},
        spec: {
            secretName: args.secretName,
            issuerRef: {
                name: args.issuerName,
                kind: args.issuerKind,
                group: args.issuerGroup,
            },
            dnsNames: makeDirectTypeProxy(args.dnsNames as any),
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
        .addRequiredInput("proxyName", typeToken<string>())
        .addRequiredInput("listenPort", typeToken<number>())
        .addOptionalInput("internetFacing", c => false)
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                manifest: makeProxyServiceManifest(
                    b.inputs.proxyName,
                    b.inputs.listenPort,
                    expr.deserializeRecord(b.inputs.internetFacing)
                )
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )


    .addTemplate("deployProxyDeployment", t => t
        .addRequiredInput("proxyName", typeToken<string>())
        .addRequiredInput("jsonConfig", typeToken<string>())
        .addRequiredInput("listenPort", typeToken<number>())
        .addRequiredInput("podReplicas", typeToken<number>())
        .addRequiredInput("resources", typeToken<ResourceRequirementsType>())
        .addOptionalInput("tlsSecretName", c => "")
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["CaptureProxy"]))
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                manifest: makeProxyDeploymentManifest({
                    proxyName: b.inputs.proxyName,
                    image: b.inputs.imageCaptureProxyLocation,
                    imagePullPolicy: b.inputs.imageCaptureProxyPullPolicy,
                    listenPort: b.inputs.listenPort,
                    podReplicas: expr.deserializeRecord(b.inputs.podReplicas),
                    resources: expr.deserializeRecord(b.inputs.resources),
                    jsonConfig: expr.toBase64(b.inputs.jsonConfig),
                })
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )


    .addTemplate("deployProxyDeploymentWithTls", t => t
        .addRequiredInput("proxyName", typeToken<string>())
        .addRequiredInput("jsonConfig", typeToken<string>())
        .addRequiredInput("listenPort", typeToken<number>())
        .addRequiredInput("podReplicas", typeToken<number>())
        .addRequiredInput("resources", typeToken<ResourceRequirementsType>())
        .addRequiredInput("tlsSecretName", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["CaptureProxy"]))
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                manifest: makeProxyDeploymentManifest({
                    proxyName: b.inputs.proxyName,
                    image: b.inputs.imageCaptureProxyLocation,
                    imagePullPolicy: b.inputs.imageCaptureProxyPullPolicy,
                    listenPort: b.inputs.listenPort,
                    podReplicas: expr.deserializeRecord(b.inputs.podReplicas),
                    resources: expr.deserializeRecord(b.inputs.resources),
                    jsonConfig: expr.toBase64(b.inputs.jsonConfig),
                    tlsSecretName: b.inputs.tlsSecretName,
                })
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )


    .addTemplate("provisionProxyCert", t => t
        .addRequiredInput("certName", typeToken<string>())
        .addRequiredInput("secretName", typeToken<string>())
        .addRequiredInput("issuerName", typeToken<string>())
        .addRequiredInput("issuerKind", typeToken<string>())
        .addRequiredInput("issuerGroup", typeToken<string>())
        .addRequiredInput("dnsNames", typeToken<string>())
        .addRequiredInput("duration", typeToken<string>())
        .addRequiredInput("renewBefore", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                manifest: makeCertificateManifest({
                    certName: b.inputs.certName,
                    secretName: b.inputs.secretName,
                    issuerName: b.inputs.issuerName,
                    issuerKind: b.inputs.issuerKind,
                    issuerGroup: b.inputs.issuerGroup,
                    dnsNames: b.inputs.dnsNames,
                    duration: b.inputs.duration,
                    renewBefore: b.inputs.renewBefore,
                })
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
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
        .addRequiredInput("proxyConfig", typeToken<z.infer<typeof DENORMALIZED_PROXY_CONFIG>>())
        .addRequiredInput("kafkaClusterName", typeToken<string>())
        .addRequiredInput("kafkaTopicName", typeToken<string>())
        .addRequiredInput("proxyName", typeToken<string>())
        .addRequiredInput("listenPort", typeToken<number>())
        .addRequiredInput("podReplicas", typeToken<number>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["CaptureProxy"]))

        .addSteps(b => {
            const config = expr.deserializeRecord(b.inputs.proxyConfig);
            const proxyOpts = expr.get(config, "proxyConfig") as any;
            const tlsBlock = expr.get(proxyOpts, "tls") as any;
            // Use dig so generated `when` expressions stay null-safe and avoid bracket-heavy
            // nested indexing that Argo fails to parse here.
            const tlsMode = expr.dig(proxyOpts, ["tls", "mode"], expr.literal("")) as any;
            const hasCertManagerTls = expr.equals(tlsMode, "certManager") as any;
            const hasExistingSecretTls = expr.equals(tlsMode, "existingSecret") as any;
            const hasTls = expr.or(hasCertManagerTls, hasExistingSecretTls) as unknown as BaseExpression<boolean, "complicatedExpression">;
            // Secret name: for certManager mode, use <proxyName>-tls; for existingSecret, extract from config
            const certManagerSecretName = expr.concat(b.inputs.proxyName, expr.literal("-tls"));
            const existingSecretName = expr.get(tlsBlock, "secretName") as any;
            const tlsSecretName = expr.ternary(hasCertManagerTls, certManagerSecretName,
                expr.ternary(hasExistingSecretTls, existingSecretName, expr.literal(""))) as any;
            // Issuer fields for cert provisioning
            const issuerRef = expr.get(tlsBlock, "issuerRef") as any;

            return b
                .addStep("createKafkaTopic", SetupKafka, "createKafkaTopicWithRetry", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        clusterName: b.inputs.kafkaClusterName,
                        topicName: b.inputs.kafkaTopicName,
                        clusterConfig: expr.serialize(expr.literal({})),
                        retryGroupName_view: expr.concat(expr.literal("KafkaTopic: "), b.inputs.kafkaTopicName),
                    })
                )
                .addStepGroup(g => g
                    .addStep("deployService", INTERNAL, "deployProxyService", c =>
                        c.register({
                            proxyName: b.inputs.proxyName,
                            listenPort: b.inputs.listenPort,
                            internetFacing: expr.dig(proxyOpts, ["internetFacing"], false),
                        })
                    )
                    .addStep("provisionCert", INTERNAL, "provisionProxyCert", c =>
                            c.register({
                                certName: certManagerSecretName,
                                secretName: certManagerSecretName,
                                issuerName: expr.get(issuerRef, "name") as any,
                                issuerKind: expr.get(issuerRef, "kind") as any,
                                issuerGroup: expr.get(issuerRef, "group") as any,
                                dnsNames: expr.recordToString(expr.get(tlsBlock, "dnsNames") as any),
                                duration: expr.get(tlsBlock, "duration") as any,
                                renewBefore: expr.get(tlsBlock, "renewBefore") as any,
                            }),
                        {when: {templateExp: hasCertManagerTls}}
                    )
                )
                .addStep("waitForCert", INTERNAL, "waitForCertReady", c =>
                        c.register({
                            certName: certManagerSecretName,
                        }),
                    {when: {templateExp: hasCertManagerTls}}
                )
                .addStepGroup(g => g
                    .addStep("deployProxyNoTls", INTERNAL, "deployProxyDeployment", c =>
                            c.register({
                                ...selectInputsForRegister(b, c),
                                proxyName: expr.get(expr.deserializeRecord(b.inputs.proxyConfig), "name"),
                                listenPort: b.inputs.listenPort,
                                podReplicas: b.inputs.podReplicas,
                                resources: expr.serialize(expr.get(proxyOpts, "resources") as any),
                                jsonConfig: expr.asString(expr.serialize(
                                    makeProxyParamsDict(b.inputs.proxyConfig) as any
                                )),
                            }),
                        {when: {templateExp: expr.not(hasTls)}}
                    )
                    .addStep("deployProxyWithTls", INTERNAL, "deployProxyDeploymentWithTls", c =>
                            c.register({
                                ...selectInputsForRegister(b, c),
                                proxyName: expr.get(expr.deserializeRecord(b.inputs.proxyConfig), "name"),
                                listenPort: b.inputs.listenPort,
                                podReplicas: b.inputs.podReplicas,
                                resources: expr.serialize(expr.get(proxyOpts, "resources") as any),
                                tlsSecretName: tlsSecretName,
                                jsonConfig: expr.asString(expr.serialize(
                                    expr.mergeDicts(
                                        makeProxyParamsDict(b.inputs.proxyConfig) as any,
                                        expr.makeDict({
                                            sslCertChainFile: expr.literal("/etc/proxy-tls/tls.crt"),
                                            sslKeyFile: expr.literal("/etc/proxy-tls/tls.key"),
                                        })
                                    ) as any
                                )),
                            }),
                        {when: {templateExp: hasTls}}
                    )
                );
        })
    )


    .getFullScope();
