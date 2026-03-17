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
import {ResourceManagement} from "./resourceManagement";

const KAFKA_AUTH_CONFIG_MOUNT_PATH = "/config/kafka-auth";
const KAFKA_AUTH_CONFIG_FILE_PATH = `${KAFKA_AUTH_CONFIG_MOUNT_PATH}/client.properties`;
const KAFKA_CA_MOUNT_PATH = "/config/kafka-ca";

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
    proxyConfig: BaseExpression<Serialized<z.infer<typeof DENORMALIZED_PROXY_CONFIG>>>,
    resolvedKafkaConnection: BaseExpression<string>,
    resolvedKafkaListenerName: BaseExpression<string>,
    resolvedKafkaAuthType: BaseExpression<string>,
) {
    const config = expr.deserializeRecord(proxyConfig);
    const kafkaConfig = expr.get(config, "kafkaConfig");
    const effectiveKafkaConnection = expr.ternary(
        expr.not(expr.equals(resolvedKafkaConnection, expr.literal(""))),
        resolvedKafkaConnection,
        expr.getLoose(kafkaConfig, "kafkaConnection")
    );
    const effectiveKafkaListenerName = expr.ternary(
        expr.not(expr.equals(resolvedKafkaListenerName, expr.literal(""))),
        resolvedKafkaListenerName,
        expr.getLoose(kafkaConfig, "listenerName")
    );
    const effectiveKafkaAuthType = expr.ternary(
        expr.not(expr.equals(resolvedKafkaAuthType, expr.literal(""))),
        resolvedKafkaAuthType,
        expr.getLoose(kafkaConfig, "authType")
    );
    const effectiveKafkaSecretName = expr.getLoose(kafkaConfig, "secretName");
    const effectiveKafkaUserName = expr.getLoose(kafkaConfig, "kafkaUserName");
    const shouldUseScram = expr.equals(effectiveKafkaAuthType, expr.literal("scram-sha-512"));
    return expr.mergeDicts(
        expr.omit(expr.get(config, "proxyConfig"), ...ARGO_PROXY_WORKFLOW_OPTION_KEYS),
        expr.mergeDicts(
            expr.ternary(expr.dig(config, ["proxyConfig", "noCapture"], false),
                expr.literal({}),
                expr.makeDict({
                    kafkaConnection: effectiveKafkaConnection
                })
            ),
            expr.mergeDicts(
                expr.makeDict({
                    destinationUri: expr.get(config, "sourceEndpoint"),
                    insecureDestination: expr.get(config, "sourceAllowInsecure"),
                    kafkaTopic: expr.jsonPathStrict(proxyConfig, "kafkaConfig", "kafkaTopic"),
                    kafkaListenerName: effectiveKafkaListenerName,
                    kafkaAuthType: effectiveKafkaAuthType,
                    kafkaSecretName: effectiveKafkaSecretName,
                    kafkaUserName: effectiveKafkaUserName,
                }),
                expr.ternary(
                    shouldUseScram,
                    expr.makeDict({
                        kafkaPropertyFile: expr.literal(KAFKA_AUTH_CONFIG_FILE_PATH),
                    }),
                    expr.literal({})
                )
            )
        )
    );
}

function makeKafkaClientPropertiesConfigMap(name: BaseExpression<string>) {
    return {
        apiVersion: "v1",
        kind: "ConfigMap",
        metadata: {name},
        data: {
            "client.properties": [
                "ssl.truststore.type=PEM",
                `ssl.truststore.location=${KAFKA_CA_MOUNT_PATH}/ca.crt`,
            ].join("\n")
        }
    };
}

function makeProxyConfigManifest(
    proxyConfig: BaseExpression<Serialized<z.infer<typeof DENORMALIZED_PROXY_CONFIG>>>,
    proxyName: BaseExpression<string>
) {
    const config = expr.deserializeRecord(proxyConfig);
    const proxyOpts = expr.get(config, "proxyConfig") as any;
    return {
        apiVersion: "migrations.opensearch.org/v1alpha1",
        kind: "ProxyConfig",
        metadata: {
            name: proxyName,
            labels: {
                "workflows.argoproj.io/run-uid": makeStringTypeProxy(expr.getWorkflowValue("uid"))
            }
        },
        spec: {
            loggingConfigurationOverrideConfigMap: makeStringTypeProxy(
                expr.dig(proxyOpts, ["loggingConfigurationOverrideConfigMap"], expr.literal(""))
            ),
            internetFacing: makeDirectTypeProxy(expr.dig(proxyOpts, ["internetFacing"], false)),
            podReplicas: makeDirectTypeProxy(expr.dig(proxyOpts, ["podReplicas"], 1)),
            resources: makeDirectTypeProxy(expr.get(proxyOpts, "resources") as any),
            otelCollectorEndpoint: makeStringTypeProxy(
                expr.dig(proxyOpts, ["otelCollectorEndpoint"], expr.literal("http://otel-collector:4317"))
            ),
            setHeader: makeDirectTypeProxy(expr.dig(proxyOpts, ["setHeader"], expr.literal([])) as any),
            destinationConnectionPoolSize: makeDirectTypeProxy(
                expr.dig(proxyOpts, ["destinationConnectionPoolSize"], 0)
            ),
            destinationConnectionPoolTimeout: makeStringTypeProxy(
                expr.dig(proxyOpts, ["destinationConnectionPoolTimeout"], expr.literal("PT30S"))
            ),
            kafkaClientId: makeStringTypeProxy(
                expr.dig(proxyOpts, ["kafkaClientId"], expr.literal("HttpCaptureProxyProducer"))
            ),
            listenPort: makeDirectTypeProxy(expr.get(proxyOpts, "listenPort") as any),
            maxTrafficBufferSize: makeDirectTypeProxy(expr.dig(proxyOpts, ["maxTrafficBufferSize"], 1048576)),
            noCapture: makeDirectTypeProxy(expr.dig(proxyOpts, ["noCapture"], false)),
            numThreads: makeDirectTypeProxy(expr.dig(proxyOpts, ["numThreads"], 1)),
            sslConfigFile: makeStringTypeProxy(expr.dig(proxyOpts, ["sslConfigFile"], expr.literal(""))),
            tls: makeDirectTypeProxy(expr.dig(proxyOpts, ["tls"], expr.literal({})) as any),
            enableMSKAuth: makeDirectTypeProxy(expr.dig(proxyOpts, ["enableMSKAuth"], false)),
            suppressCaptureForHeaderMatch: makeDirectTypeProxy(
                expr.dig(proxyOpts, ["suppressCaptureForHeaderMatch"], expr.literal([])) as any
            ),
            suppressCaptureForMethod: makeStringTypeProxy(
                expr.dig(proxyOpts, ["suppressCaptureForMethod"], expr.literal(""))
            ),
            suppressCaptureForUriPath: makeStringTypeProxy(
                expr.dig(proxyOpts, ["suppressCaptureForUriPath"], expr.literal(""))
            ),
            suppressMethodAndPath: makeStringTypeProxy(
                expr.dig(proxyOpts, ["suppressMethodAndPath"], expr.literal(""))
            ),
        }
    };
}

function makeProxyDeploymentManifest(args: {
    proxyName: BaseExpression<string>,
    image: BaseExpression<string>,
    imagePullPolicy: BaseExpression<string>,
    listenPort: BaseExpression<Serialized<number>>,
    podReplicas: BaseExpression<number>,
    resources: BaseExpression<ResourceRequirementsType>,
    jsonConfig: BaseExpression<string>,
    kafkaAuthConfigMapName: BaseExpression<string>,
    kafkaAuthType: BaseExpression<string>,
    kafkaSecretName: BaseExpression<string>,
    kafkaCaSecretName: BaseExpression<string>,
    tlsSecretName?: BaseExpression<string>,
}) {
    const isScramAuth = expr.equals(args.kafkaAuthType, expr.literal("scram-sha-512"));
    const container: Record<string, any> = {
        name: "proxy",
        image: args.image,
        imagePullPolicy: args.imagePullPolicy,
        args: [
            "---INLINE-JSON",
            makeStringTypeProxy(args.jsonConfig)
        ],
        ports: [{containerPort: makeDirectTypeProxy(args.listenPort)}],
        resources: makeDirectTypeProxy(args.resources),
        env: [
            {
                name: "CAPTURE_PROXY_KAFKA_PASSWORD",
                valueFrom: {
                    secretKeyRef: {
                        name: makeStringTypeProxy(expr.ternary(
                            isScramAuth,
                            args.kafkaSecretName,
                            expr.literal("empty")
                        )),
                        key: "password",
                        optional: makeDirectTypeProxy(expr.not(isScramAuth))
                    }
                }
            }
        ],
        volumeMounts: [
            {
                name: "kafka-auth-config",
                mountPath: KAFKA_AUTH_CONFIG_MOUNT_PATH,
                readOnly: true
            },
            {
                name: "kafka-ca",
                mountPath: KAFKA_CA_MOUNT_PATH,
                readOnly: true
            }
        ]
    };
    if (args.tlsSecretName) {
        container.volumeMounts.push({name: "tls-certs", mountPath: "/etc/proxy-tls", readOnly: true});
    }

    const podSpec: Record<string, any> = {
        containers: [container],
        volumes: [
            {
                name: "kafka-auth-config",
                configMap: {name: args.kafkaAuthConfigMapName}
            },
            {
                name: "kafka-ca",
                secret: {
                    secretName: makeStringTypeProxy(expr.ternary(
                        isScramAuth,
                        args.kafkaCaSecretName,
                        expr.literal("empty")
                    )),
                    optional: makeDirectTypeProxy(expr.not(isScramAuth))
                }
            }
        ]
    };
    if (args.tlsSecretName) {
        podSpec.volumes.push({name: "tls-certs", secret: {secretName: args.tlsSecretName}});
    }

    return {
        apiVersion: "apps/v1",
        kind: "Deployment",
        metadata: {name: args.proxyName},
        spec: {
            replicas: makeDirectTypeProxy(args.podReplicas),
            strategy: {
                type: "Recreate"
            },
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

    .addTemplate("suspendForRetry", t => t
        .addRequiredInput("name", typeToken<string>())
        .addSuspend()
    )

    .addTemplate("applyProxyConfig", t => t
        .addRequiredInput("proxyConfig", typeToken<z.infer<typeof DENORMALIZED_PROXY_CONFIG>>())
        .addRequiredInput("proxyName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                manifest: makeProxyConfigManifest(b.inputs.proxyConfig, b.inputs.proxyName)
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )

    .addTemplate("applyProxyConfigWithRetry", t => t
        .addRequiredInput("proxyConfig", typeToken<z.infer<typeof DENORMALIZED_PROXY_CONFIG>>())
        .addRequiredInput("proxyName", typeToken<string>())
        .addOptionalInput("retryGroupName_view", c => "Apply")

        .addSteps(b => b
            .addStep("tryApply", INTERNAL, "applyProxyConfig", c =>
                c.register({
                    proxyConfig: b.inputs.proxyConfig,
                    proxyName: b.inputs.proxyName,
                }),
                {continueOn: {failed: true}}
            )
            .addStep("waitForFix", INTERNAL, "suspendForRetry", c =>
                c.register({
                    name: expr.literal("ProxyConfig")
                }),
                {when: c => ({templateExp: expr.equals(c.tryApply.status, "Failed")})}
            )
            .addStep("patchApproval", ResourceManagement, "patchApprovalAnnotation", c =>
                c.register({
                    resourceApiVersion: expr.literal("migrations.opensearch.org/v1alpha1"),
                    resourceKind: expr.literal("ProxyConfig"),
                    resourceName: b.inputs.proxyName,
                }),
                {when: c => ({templateExp: expr.equals(c.waitForFix.status, "Succeeded")})}
            )
            .addStepToSelf("retryLoop", c =>
                c.register({
                    proxyConfig: b.inputs.proxyConfig,
                    proxyName: b.inputs.proxyName,
                    retryGroupName_view: b.inputs.retryGroupName_view,
                }),
                {when: c => ({templateExp: expr.equals(c.patchApproval.status, "Succeeded")})}
            )
        )
    )


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
        .addRequiredInput("kafkaAuthConfigMapName", typeToken<string>())
        .addRequiredInput("kafkaAuthType", typeToken<string>())
        .addRequiredInput("kafkaSecretName", typeToken<string>())
        .addRequiredInput("kafkaCaSecretName", typeToken<string>())
        .addRequiredInput("listenPort", typeToken<number>())
        .addRequiredInput("podReplicas", typeToken<number>())
        .addRequiredInput("resources", typeToken<ResourceRequirementsType>())
        .addOptionalInput("tlsSecretName", c => "")
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["CaptureProxy"]))
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                successCondition: "status.readyReplicas > 0",
                manifest: makeProxyDeploymentManifest({
                    proxyName: b.inputs.proxyName,
                    image: b.inputs.imageCaptureProxyLocation,
                    imagePullPolicy: b.inputs.imageCaptureProxyPullPolicy,
                    listenPort: b.inputs.listenPort,
                    podReplicas: expr.deserializeRecord(b.inputs.podReplicas),
                    resources: expr.deserializeRecord(b.inputs.resources),
                    jsonConfig: expr.toBase64(b.inputs.jsonConfig),
                    kafkaAuthConfigMapName: b.inputs.kafkaAuthConfigMapName,
                    kafkaAuthType: b.inputs.kafkaAuthType,
                    kafkaSecretName: b.inputs.kafkaSecretName,
                    kafkaCaSecretName: b.inputs.kafkaCaSecretName,
                })
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )


    .addTemplate("deployProxyDeploymentWithTls", t => t
        .addRequiredInput("proxyName", typeToken<string>())
        .addRequiredInput("jsonConfig", typeToken<string>())
        .addRequiredInput("kafkaAuthConfigMapName", typeToken<string>())
        .addRequiredInput("kafkaAuthType", typeToken<string>())
        .addRequiredInput("kafkaSecretName", typeToken<string>())
        .addRequiredInput("kafkaCaSecretName", typeToken<string>())
        .addRequiredInput("listenPort", typeToken<number>())
        .addRequiredInput("podReplicas", typeToken<number>())
        .addRequiredInput("resources", typeToken<ResourceRequirementsType>())
        .addRequiredInput("tlsSecretName", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["CaptureProxy"]))
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                successCondition: "status.readyReplicas > 0",
                manifest: makeProxyDeploymentManifest({
                    proxyName: b.inputs.proxyName,
                    image: b.inputs.imageCaptureProxyLocation,
                    imagePullPolicy: b.inputs.imageCaptureProxyPullPolicy,
                    listenPort: b.inputs.listenPort,
                    podReplicas: expr.deserializeRecord(b.inputs.podReplicas),
                    resources: expr.deserializeRecord(b.inputs.resources),
                    jsonConfig: expr.toBase64(b.inputs.jsonConfig),
                    kafkaAuthConfigMapName: b.inputs.kafkaAuthConfigMapName,
                    kafkaAuthType: b.inputs.kafkaAuthType,
                    kafkaSecretName: b.inputs.kafkaSecretName,
                    kafkaCaSecretName: b.inputs.kafkaCaSecretName,
                    tlsSecretName: b.inputs.tlsSecretName,
                })
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )


    .addTemplate("createKafkaClientPropertiesConfigMap", t => t
        .addRequiredInput("name", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                manifest: makeKafkaClientPropertiesConfigMap(b.inputs.name)
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
        .addOptionalInput("resolvedKafkaConnection", c => "")
        .addOptionalInput("resolvedKafkaListenerName", c => "")
        .addOptionalInput("resolvedKafkaAuthType", c => "")
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
            const kafkaConfig = expr.get(config, "kafkaConfig");
            const effectiveKafkaAuthType = expr.ternary(
                expr.not(expr.equals(b.inputs.resolvedKafkaAuthType, expr.literal(""))),
                b.inputs.resolvedKafkaAuthType,
                expr.getLoose(kafkaConfig, "authType")
            );
            const kafkaAuthConfigMapName = expr.concat(b.inputs.proxyName, expr.literal("-kafka-auth"));
            // Issuer fields for cert provisioning
            const issuerRef = expr.get(tlsBlock, "issuerRef") as any;

            return b
                .addStep("createKafkaTopic", SetupKafka, "createKafkaTopicWithRetry", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        clusterName: b.inputs.kafkaClusterName,
                        topicName: b.inputs.kafkaTopicName,
                        partitions: expr.dig(
                            expr.get(expr.deserializeRecord(b.inputs.proxyConfig), "kafkaConfig"),
                            ["topicSpecOverrides", "partitions"],
                            1
                        ),
                        replicas: expr.dig(
                            expr.get(expr.deserializeRecord(b.inputs.proxyConfig), "kafkaConfig"),
                            ["topicSpecOverrides", "replicas"],
                            1
                        ),
                        topicConfig: expr.dig(
                            expr.get(expr.deserializeRecord(b.inputs.proxyConfig), "kafkaConfig"),
                            ["topicSpecOverrides", "config"],
                            {}
                        ),
                        retryGroupName_view: expr.concat(expr.literal("KafkaTopic: "), b.inputs.kafkaTopicName),
                    })
                )
                .addStep("createKafkaClientConfig", INTERNAL, "createKafkaClientPropertiesConfigMap", c =>
                    c.register({
                        name: kafkaAuthConfigMapName
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
                                kafkaAuthConfigMapName,
                                kafkaAuthType: effectiveKafkaAuthType,
                                kafkaSecretName: expr.getLoose(kafkaConfig, "secretName"),
                                kafkaCaSecretName: expr.getLoose(kafkaConfig, "caSecretName"),
                                resources: expr.serialize(expr.get(proxyOpts, "resources") as any),
                                jsonConfig: expr.asString(expr.serialize(
                                    makeProxyParamsDict(
                                        b.inputs.proxyConfig,
                                        b.inputs.resolvedKafkaConnection,
                                        b.inputs.resolvedKafkaListenerName,
                                        b.inputs.resolvedKafkaAuthType
                                    ) as any
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
                                kafkaAuthConfigMapName,
                                kafkaAuthType: effectiveKafkaAuthType,
                                kafkaSecretName: expr.getLoose(kafkaConfig, "secretName"),
                                kafkaCaSecretName: expr.getLoose(kafkaConfig, "caSecretName"),
                                resources: expr.serialize(expr.get(proxyOpts, "resources") as any),
                                tlsSecretName: tlsSecretName,
                                jsonConfig: expr.asString(expr.serialize(
                                    expr.mergeDicts(
                                        makeProxyParamsDict(
                                            b.inputs.proxyConfig,
                                            b.inputs.resolvedKafkaConnection,
                                            b.inputs.resolvedKafkaListenerName,
                                            b.inputs.resolvedKafkaAuthType
                                        ) as any,
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

    .addTemplate("setupProxyWithLifecycle", t => t
        .addRequiredInput("proxyConfig", typeToken<z.infer<typeof DENORMALIZED_PROXY_CONFIG>>())
        .addRequiredInput("kafkaClusterName", typeToken<string>())
        .addRequiredInput("kafkaTopicName", typeToken<string>())
        .addRequiredInput("proxyName", typeToken<string>())
        .addRequiredInput("listenPort", typeToken<number>())
        .addRequiredInput("podReplicas", typeToken<number>())
        .addOptionalInput("resolvedKafkaConnection", c => "")
        .addOptionalInput("resolvedKafkaListenerName", c => "")
        .addOptionalInput("resolvedKafkaAuthType", c => "")
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["CaptureProxy"]))

        .addSteps(b => b
            .addStep("applyProxyConfig", INTERNAL, "applyProxyConfigWithRetry", c =>
                c.register({
                    proxyConfig: b.inputs.proxyConfig,
                    proxyName: b.inputs.proxyName,
                    retryGroupName_view: expr.concat(expr.literal("ProxyConfig: "), b.inputs.proxyName),
                })
            )
            .addStep("markRunning", ResourceManagement, "patchResourcePhase", c =>
                c.register({
                    resourceKind: expr.literal("CapturedTraffic"),
                    resourceName: b.inputs.proxyName,
                    phase: expr.literal("Running"),
                })
            )
            .addStep("setupProxy", INTERNAL, "setupProxy", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    proxyConfig: b.inputs.proxyConfig,
                    kafkaClusterName: b.inputs.kafkaClusterName,
                    kafkaTopicName: b.inputs.kafkaTopicName,
                    proxyName: b.inputs.proxyName,
                    listenPort: b.inputs.listenPort,
                    podReplicas: b.inputs.podReplicas,
                    resolvedKafkaConnection: b.inputs.resolvedKafkaConnection,
                    resolvedKafkaListenerName: b.inputs.resolvedKafkaListenerName,
                    resolvedKafkaAuthType: b.inputs.resolvedKafkaAuthType,
                }),
                {continueOn: {failed: true}}
            )
            .addStep("markReady", ResourceManagement, "patchResourcePhase", c =>
                c.register({
                    resourceKind: expr.literal("CapturedTraffic"),
                    resourceName: b.inputs.proxyName,
                    phase: expr.literal("Ready"),
                }),
                {when: c => ({templateExp: expr.equals(c.setupProxy.status, "Succeeded")})}
            )
            .addStep("markError", ResourceManagement, "patchResourcePhase", c =>
                c.register({
                    resourceKind: expr.literal("CapturedTraffic"),
                    resourceName: b.inputs.proxyName,
                    phase: expr.literal("Error"),
                }),
                {when: c => ({templateExp: expr.equals(c.setupProxy.status, "Failed")})}
            )
        )
    )


    .getFullScope();
