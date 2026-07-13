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
import {OwnerReference} from "@opensearch-migrations/k8s-types";
import {CommonWorkflowParameters, workflowScriptCommand, workflowScriptRootEnvVars} from "./commonUtils/workflowParameters";
import {
    ARGO_PROXY_WORKFLOW_OPTION_KEYS,
    ARGO_FILE_SOURCE_VOLUME,
    ARGO_FILE_SOURCE_VOLUME_MOUNT,
    DEFAULT_RESOURCES,
    DENORMALIZED_PROXY_CONFIG,
    KAFKA_AUTH_CONFIG_FILE_PATH,
    KAFKA_AUTH_CONFIG_MOUNT_PATH,
    KAFKA_CA_MOUNT_PATH,
    PROXY_TLS_CONFIG,
    ResourceRequirementsType,
} from "@opensearch-migrations/schemas";
import {makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";
import {SetupKafka} from "./setupKafka";
import {z} from "zod";
import {
    CERT_MANAGER_WEBHOOK_RETRY_STRATEGY,
    K8S_INFRA_READY_RETRY_STRATEGY,
    K8S_INFRA_READY_TIMEOUT_SECONDS,
    K8S_RESOURCE_RETRY_STRATEGY,
} from "./commonUtils/resourceRetryStrategy";
import {CONTAINER_NAMES} from "../containerNames";
import {ResourceManagement} from "./resourceManagement";
import {setupFileSourcesForContainer} from "./commonUtils/containerFragments";
import {makePodDisruptionBudgetDefinition} from "./commonUtils/podDisruptionBudget";
import {
    MIN_POD_REPLICAS_INPUTS,
    POD_REPLICAS_INPUTS,
    SCALABLE_WORKLOAD_INPUTS,
    workflowParameterAsNumber,
} from "./commonUtils/scalableWorkload";

const CAPTURE_PROXY_SSL_TRUST_CERT_PEM_ENV_VAR = "CAPTURE_PROXY_SSL_TRUST_CERT_PEM";

function makeOwnerReferences(
    ownerName: BaseExpression<string>,
    ownerUid: BaseExpression<string>,
): OwnerReference[] {
    return [{
        apiVersion: "migrations.opensearch.org/v1alpha1",
        kind: "CaptureProxy",
        name: makeStringTypeProxy(ownerName),
        uid: makeStringTypeProxy(ownerUid),
        controller: true,
        blockOwnerDeletion: true,
    }];
}

function makeProxyServiceManifest(
    proxyName: BaseExpression<string>,
    listenPort: BaseExpression<Serialized<number>>,
    serviceType: BaseExpression<string>,
    internetFacing: BaseExpression<boolean>,
    ownerUid: BaseExpression<string>,
) {
    return {
        apiVersion: "v1",
        kind: "Service",
        metadata: {
            name: proxyName,
            ownerReferences: makeOwnerReferences(proxyName, ownerUid),
            annotations: {
                // NLB IP mode for EKS Auto Mode — ignored on non-EKS and non-LoadBalancer Services.
                "service.beta.kubernetes.io/aws-load-balancer-type": "external",
                "service.beta.kubernetes.io/aws-load-balancer-nlb-target-type": "ip",
                "service.beta.kubernetes.io/aws-load-balancer-scheme": makeStringTypeProxy(
                    expr.ternary(internetFacing, expr.literal("internet-facing"), expr.literal("internal"))
                )
            }
        },
        spec: {
            type: makeStringTypeProxy(serviceType),
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
    const shouldUseKafkaPropertyFile = expr.not(expr.equals(
        expr.dig(kafkaConfig, ["producerClientProperties"], ""),
        expr.literal("")
    ));
    return expr.mergeDicts(
        expr.omit(expr.get(config, "proxyConfig"), ...ARGO_PROXY_WORKFLOW_OPTION_KEYS),
        expr.mergeDicts(
            expr.ternary(expr.dig(config, ["proxyConfig", "noCapture"], false),
                expr.makeDict({}),
                expr.makeDict({
                    kafkaConnection: effectiveKafkaConnection
                })
            ),
            expr.mergeDicts(
                expr.makeDict({
                    destinationUri: expr.dig(config, ["sourceConfig", "endpoint"], ""),
                    insecureDestination: expr.dig(config, ["sourceConfig", "allowInsecure"], false),
                    kafkaTopic: expr.jsonPathStrict(proxyConfig, "kafkaConfig", "kafkaTopic"),
                    kafkaListenerName: effectiveKafkaListenerName,
                    kafkaAuthType: effectiveKafkaAuthType,
                    kafkaSecretName: effectiveKafkaSecretName,
                    kafkaUserName: effectiveKafkaUserName,
                }),
                expr.ternary(
                    shouldUseKafkaPropertyFile,
                    expr.makeDict({
                        kafkaPropertyFile: expr.literal(KAFKA_AUTH_CONFIG_FILE_PATH),
                    }),
                    expr.makeDict({})
                )
            )
        )
    );
}

function makeKafkaClientPropertiesConfigMap(
    name: BaseExpression<string>,
    clientProperties: BaseExpression<string>
) {
    return {
        apiVersion: "v1",
        kind: "ConfigMap",
        metadata: {name},
        data: {
            "client.properties": makeStringTypeProxy(clientProperties)
        }
    };
}

function checksumNotDone(
    actualChecksum: BaseExpression<string>,
    desiredChecksum: BaseExpression<string>,
): BaseExpression<boolean, "complicatedExpression"> {
    return expr.and(expr.literal(true), expr.not(expr.equals(actualChecksum, desiredChecksum)));
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
    sslTrustCertPem: BaseExpression<string>,
    fileSourceVolumes: BaseExpression<any[]>,
    fileSourceVolumeMounts: BaseExpression<any[]>,
    ownerUid: BaseExpression<string>,
    workflowName: BaseExpression<string>,
    sourceK8sLabel: BaseExpression<string>,
    taskK8sLabel: BaseExpression<string>,
}) {
    const isScramAuth = expr.equals(args.kafkaAuthType, expr.literal("scram-sha-512"));
    const container: Record<string, any> = {
        name: CONTAINER_NAMES.PROXY,
        image: args.image,
        imagePullPolicy: args.imagePullPolicy,
        args: [
            "---INLINE-JSON",
            makeStringTypeProxy(args.jsonConfig)
        ],
        ports: [{containerPort: makeDirectTypeProxy(args.listenPort)}],
        resources: makeDirectTypeProxy(args.resources),
        readinessProbe: {
            tcpSocket: {
                port: makeDirectTypeProxy(args.listenPort)
            },
            initialDelaySeconds: 5,
            periodSeconds: 5,
            timeoutSeconds: 3,
            failureThreshold: 3
        },
        env: [
            {
                name: "CAPTURE_PROXY_KAFKA_PASSWORD",
                valueFrom: {
                    secretKeyRef: {
                        name: makeStringTypeProxy(args.kafkaSecretName),
                        key: "password",
                        optional: makeDirectTypeProxy(expr.not(isScramAuth))
                    }
                }
            },
            {
                name: CAPTURE_PROXY_SSL_TRUST_CERT_PEM_ENV_VAR,
                value: makeStringTypeProxy(args.sslTrustCertPem)
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

    const volumes = [
            {
                name: "kafka-auth-config",
                configMap: {name: args.kafkaAuthConfigMapName}
            },
            {
                name: "kafka-ca",
                secret: {
                    secretName: makeStringTypeProxy(args.kafkaCaSecretName),
                    optional: makeDirectTypeProxy(expr.not(isScramAuth))
                }
            }
        ];
    if (args.tlsSecretName) {
        volumes.push({name: "tls-certs", secret: {secretName: makeStringTypeProxy(args.tlsSecretName)}} as any);
    }
    const podConfig = setupFileSourcesForContainer(
        args.fileSourceVolumes,
        args.fileSourceVolumeMounts,
        {container: container as any, volumes: volumes as any}
    );

    return {
        apiVersion: "apps/v1",
        kind: "Deployment",
        metadata: {
            name: args.proxyName,
            ownerReferences: makeOwnerReferences(args.proxyName, args.ownerUid),
            labels: {
                "workflows.argoproj.io/workflow": makeStringTypeProxy(args.workflowName),
                "migrations.opensearch.org/source": makeStringTypeProxy(args.sourceK8sLabel),
                "migrations.opensearch.org/task": makeStringTypeProxy(args.taskK8sLabel),
            },
        },
        spec: {
            replicas: makeDirectTypeProxy(args.podReplicas),
            minReadySeconds: 2,
            strategy: {
                type: "Recreate"
            },
            selector: {matchLabels: {"migrations/proxy": args.proxyName}},
            template: {
                metadata: {labels: {
                    "migrations/proxy": args.proxyName,
                    "workflows.argoproj.io/workflow": makeStringTypeProxy(args.workflowName),
                    "migrations.opensearch.org/source": makeStringTypeProxy(args.sourceK8sLabel),
                    "migrations.opensearch.org/task": makeStringTypeProxy(args.taskK8sLabel),
                }},
                spec: {
                    containers: [podConfig.container],
                    volumes: podConfig.volumes
                }
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
    dnsNames: BaseExpression<Serialized<string[]>>,
    duration: BaseExpression<string>,
    renewBefore: BaseExpression<string>,
    ownerName: BaseExpression<string>,
    ownerUid: BaseExpression<string>,
}) {
    // dnsNames must be rendered as a raw JSON array in the manifest, not quoted.
    // We use makeDirectTypeProxy to prevent quoting so the JSON array is inlined directly.
    return {
        apiVersion: "cert-manager.io/v1",
        kind: "Certificate",
        metadata: {
            name: args.certName,
            ownerReferences: makeOwnerReferences(args.ownerName, args.ownerUid),
        },
        spec: {
            secretName: args.secretName,
            issuerRef: {
                name: args.issuerName,
                kind: args.issuerKind,
                group: args.issuerGroup,
            },
            dnsNames: makeDirectTypeProxy(args.dnsNames),
            duration: args.duration,
            renewBefore: args.renewBefore,
            privateKey: {
                algorithm: "RSA",
                encoding: "PKCS8",
                size: 2048,
            },
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
        .addRequiredInput("ownerUid", typeToken<string>())
        .addOptionalInput("serviceType", c => "LoadBalancer")
        .addOptionalInput("internetFacing", c => false)
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                manifest: makeProxyServiceManifest(
                    b.inputs.proxyName,
                    b.inputs.listenPort,
                    b.inputs.serviceType,
                    expr.deserializeRecord(b.inputs.internetFacing),
                    b.inputs.ownerUid,
                )
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )


    .addTemplate("deployProxyPodDisruptionBudget", t => t
        .addRequiredInput("proxyName", typeToken<string>())
        .addInputsFromRecord(MIN_POD_REPLICAS_INPUTS)
        .addRequiredInput("ownerUid", typeToken<string>())
        .addRequiredInput("sourceK8sLabel", typeToken<string>())
        .addOptionalInput("taskK8sLabel", c => "captureProxy")
        .addResourceTask(b => b
            .setDefinition(makePodDisruptionBudgetDefinition({
                name: b.inputs.proxyName,
                minAvailable: workflowParameterAsNumber(b.inputs.minPodReplicas),
                matchLabels: {"migrations/proxy": b.inputs.proxyName},
                labels: {
                    "workflows.argoproj.io/workflow": expr.getWorkflowValue("name"),
                    "migrations.opensearch.org/source": b.inputs.sourceK8sLabel,
                    "migrations.opensearch.org/task": b.inputs.taskK8sLabel,
                },
                ownerReferences: makeOwnerReferences(b.inputs.proxyName, b.inputs.ownerUid),
            }))
        )
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
        .addInputsFromRecord(POD_REPLICAS_INPUTS)
        .addRequiredInput("resources", typeToken<ResourceRequirementsType>())
        .addRequiredInput("sslTrustCertPem", typeToken<string>())
        .addRequiredInput("fileSourceVolumes", typeToken<z.infer<typeof ARGO_FILE_SOURCE_VOLUME>[]>())
        .addRequiredInput("fileSourceVolumeMounts", typeToken<z.infer<typeof ARGO_FILE_SOURCE_VOLUME_MOUNT>[]>())
        .addRequiredInput("ownerUid", typeToken<string>())
        .addRequiredInput("sourceK8sLabel", typeToken<string>())
        .addOptionalInput("taskK8sLabel", c => "captureProxy")
        .addOptionalInput("tlsSecretName", c => "")
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["CaptureProxy"]))
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                activeDeadlineSeconds: K8S_INFRA_READY_TIMEOUT_SECONDS,
                setOwnerReference: false,
                successCondition: "status.readyReplicas > 0",
                manifest: makeProxyDeploymentManifest({
                    proxyName: b.inputs.proxyName,
                    image: b.inputs.imageCaptureProxyLocation,
                    imagePullPolicy: b.inputs.imageCaptureProxyPullPolicy,
                    listenPort: b.inputs.listenPort,
                    podReplicas: expr.deserializeRecord(b.inputs.podReplicas),
                    resources: expr.deserializeRecord(b.inputs.resources),
                    jsonConfig: expr.toBase64YamlSafe(b.inputs.jsonConfig),
                    kafkaAuthConfigMapName: b.inputs.kafkaAuthConfigMapName,
                    kafkaAuthType: b.inputs.kafkaAuthType,
                    kafkaSecretName: b.inputs.kafkaSecretName,
                    kafkaCaSecretName: b.inputs.kafkaCaSecretName,
                    sslTrustCertPem: b.inputs.sslTrustCertPem,
                    fileSourceVolumes: expr.deserializeRecord(b.inputs.fileSourceVolumes),
                    fileSourceVolumeMounts: expr.deserializeRecord(b.inputs.fileSourceVolumeMounts),
                    ownerUid: b.inputs.ownerUid,
                    workflowName: expr.getWorkflowValue("name"),
                    sourceK8sLabel: b.inputs.sourceK8sLabel,
                    taskK8sLabel: b.inputs.taskK8sLabel,
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
        .addInputsFromRecord(POD_REPLICAS_INPUTS)
        .addRequiredInput("resources", typeToken<ResourceRequirementsType>())
        .addRequiredInput("sslTrustCertPem", typeToken<string>())
        .addRequiredInput("fileSourceVolumes", typeToken<z.infer<typeof ARGO_FILE_SOURCE_VOLUME>[]>())
        .addRequiredInput("fileSourceVolumeMounts", typeToken<z.infer<typeof ARGO_FILE_SOURCE_VOLUME_MOUNT>[]>())
        .addRequiredInput("tlsSecretName", typeToken<string>())
        .addRequiredInput("ownerUid", typeToken<string>())
        .addRequiredInput("sourceK8sLabel", typeToken<string>())
        .addOptionalInput("taskK8sLabel", c => "captureProxy")
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["CaptureProxy"]))
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                activeDeadlineSeconds: K8S_INFRA_READY_TIMEOUT_SECONDS,
                setOwnerReference: false,
                successCondition: "status.readyReplicas > 0",
                manifest: makeProxyDeploymentManifest({
                    proxyName: b.inputs.proxyName,
                    image: b.inputs.imageCaptureProxyLocation,
                    imagePullPolicy: b.inputs.imageCaptureProxyPullPolicy,
                    listenPort: b.inputs.listenPort,
                    podReplicas: expr.deserializeRecord(b.inputs.podReplicas),
                    resources: expr.deserializeRecord(b.inputs.resources),
                    jsonConfig: expr.toBase64YamlSafe(b.inputs.jsonConfig),
                    kafkaAuthConfigMapName: b.inputs.kafkaAuthConfigMapName,
                    kafkaAuthType: b.inputs.kafkaAuthType,
                    kafkaSecretName: b.inputs.kafkaSecretName,
                    kafkaCaSecretName: b.inputs.kafkaCaSecretName,
                    tlsSecretName: b.inputs.tlsSecretName,
                    sslTrustCertPem: b.inputs.sslTrustCertPem,
                    fileSourceVolumes: expr.deserializeRecord(b.inputs.fileSourceVolumes),
                    fileSourceVolumeMounts: expr.deserializeRecord(b.inputs.fileSourceVolumeMounts),
                    ownerUid: b.inputs.ownerUid,
                    workflowName: expr.getWorkflowValue("name"),
                    sourceK8sLabel: b.inputs.sourceK8sLabel,
                    taskK8sLabel: b.inputs.taskK8sLabel,
                })
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )


    .addTemplate("createKafkaClientPropertiesConfigMap", t => t
        .addRequiredInput("name", typeToken<string>())
        .addRequiredInput("clientProperties", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                manifest: makeKafkaClientPropertiesConfigMap(b.inputs.name, b.inputs.clientProperties)
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )


    .addTemplate("provisionProxyCert", t => t
        .addRequiredInput("certName", typeToken<string>())
        .addRequiredInput("secretName", typeToken<string>())
        .addRequiredInput("issuerName", typeToken<string>())
        .addRequiredInput("issuerKind", typeToken<string>())
        .addRequiredInput("issuerGroup", typeToken<string>())
        .addRequiredInput("dnsNames", typeToken<string[]>())
        .addRequiredInput("duration", typeToken<string>())
        .addRequiredInput("renewBefore", typeToken<string>())
        .addRequiredInput("ownerName", typeToken<string>())
        .addRequiredInput("ownerUid", typeToken<string>())
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
                    ownerName: b.inputs.ownerName,
                    ownerUid: b.inputs.ownerUid,
                })
            }))
        .addRetryParameters(CERT_MANAGER_WEBHOOK_RETRY_STRATEGY)
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
                }
            })
            .addRetryParameters(K8S_INFRA_READY_RETRY_STRATEGY))
    )

    .addTemplate("waitForProxyEndpointReady", t => t
        .addRequiredInput("proxyName", typeToken<string>())
        .addRequiredInput("listenPort", typeToken<number>())
        .addOptionalInput("serviceType", c => "LoadBalancer")
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addContainer(b => b
            .addImageInfo(b.inputs.imageMigrationConsoleLocation, b.inputs.imageMigrationConsolePullPolicy)
            .addCommand(["/bin/bash", "-lc"])
            .addResources(DEFAULT_RESOURCES.SHELL_MIGRATION_CONSOLE_CLI)
            .addEnvVarsFromRecord({
                NAMESPACE: expr.getWorkflowValue("namespace"),
                PROXY_NAME: b.inputs.proxyName,
                LISTEN_PORT: expr.asString(b.inputs.listenPort),
                SERVICE_TYPE: b.inputs.serviceType,
                TIMEOUT_SECONDS: expr.literal(String(K8S_INFRA_READY_TIMEOUT_SECONDS)),
                ...workflowScriptRootEnvVars(t.inputs.workflowParameters.workflowScriptsRoot)
            })
            .addArgs([workflowScriptCommand("waitForProxyEndpointReady.sh")])
            .addPathOutput("serviceEndpoint", "/tmp/service-endpoint", typeToken<string>())
            .addPathOutput("loadBalancerEndpoint", "/tmp/load-balancer-endpoint", typeToken<string>())
        )
    )


    .addTemplate("setupProxy", t => t
        .addRequiredInput("proxyConfig", typeToken<z.infer<typeof DENORMALIZED_PROXY_CONFIG>>())
        .addRequiredInput("kafkaClusterName", typeToken<string>())
        .addRequiredInput("proxyName", typeToken<string>())
        .addRequiredInput("ownerUid", typeToken<string>())
        .addRequiredInput("listenPort", typeToken<number>())
        .addInputsFromRecord(SCALABLE_WORKLOAD_INPUTS)
        .addRequiredInput("sourceK8sLabel", typeToken<string>())
        .addRequiredInput("configChecksum", typeToken<string>())
        .addRequiredInput("checksumForSnapshot", typeToken<string>())
        .addRequiredInput("checksumForReplayer", typeToken<string>())
        .addOptionalInput("resolvedKafkaConnection", c => "")
        .addOptionalInput("resolvedKafkaListenerName", c => "")
        .addOptionalInput("resolvedKafkaAuthType", c => "")
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole", "CaptureProxy"]))

        .addSteps(b => {
            const config = expr.deserializeRecord(b.inputs.proxyConfig);
            const proxyOpts = expr.get(config, "proxyConfig");
            // Argo evaluates step parameters before `when` guards, so tls fields must be null-safe.
            const tlsMode = expr.dig(proxyOpts, ["tls", "mode"], expr.literal(""));
            const hasCertManagerTls = expr.equals(tlsMode, "certManager");
            const hasExistingSecretTls = expr.equals(tlsMode, "existingSecret");
            const hasTls = expr.or(hasCertManagerTls, hasExistingSecretTls);
            const certManagerSecretName = expr.concat(b.inputs.proxyName, expr.literal("-tls"));
            const existingSecretName = expr.dig(proxyOpts, ["tls", "secretName"], expr.literal(""));
            const tlsSecretName = expr.ternary(hasCertManagerTls, certManagerSecretName,
                expr.ternary(hasExistingSecretTls, existingSecretName, expr.literal("")));
            const kafkaConfig = expr.get(config, "kafkaConfig");
            const effectiveKafkaAuthType = expr.ternary(
                expr.not(expr.equals(b.inputs.resolvedKafkaAuthType, expr.literal(""))),
                b.inputs.resolvedKafkaAuthType,
                expr.getLoose(kafkaConfig, "authType")
            );
            const shouldUseScramAuth = expr.equals(effectiveKafkaAuthType, expr.literal("scram-sha-512"));
            const kafkaAuthConfigMapName = expr.concat(b.inputs.proxyName, expr.literal("-kafka-auth"));
            const issuerName = expr.dig(proxyOpts, ["tls", "issuerRef", "name"], expr.literal(""));
            const issuerKind = expr.dig(proxyOpts, ["tls", "issuerRef", "kind"], expr.literal("ClusterIssuer"));
            const issuerGroup = expr.dig(proxyOpts, ["tls", "issuerRef", "group"], expr.literal("cert-manager.io"));
            const tlsDnsNames = expr.dig(proxyOpts, ["tls", "dnsNames"], expr.literal([]));
            const tlsDuration = expr.dig(proxyOpts, ["tls", "duration"], expr.literal("2160h"));
            const tlsRenewBefore = expr.dig(proxyOpts, ["tls", "renewBefore"], expr.literal("360h"));
            const serviceType = expr.dig(proxyOpts, ["serviceType"], expr.literal("LoadBalancer"));

            return b
                .addStep("createKafkaClientConfig", INTERNAL, "createKafkaClientPropertiesConfigMap", c =>
                    c.register({
                        name: kafkaAuthConfigMapName,
                        clientProperties: expr.dig(kafkaConfig, ["producerClientProperties"], ""),
                    })
                )
                .addStepGroup(g => g
                    .addStep("deployService", INTERNAL, "deployProxyService", c =>
                        c.register({
                            proxyName: b.inputs.proxyName,
                            listenPort: b.inputs.listenPort,
                            serviceType,
                            internetFacing: expr.dig(proxyOpts, ["internetFacing"], false),
                            ownerUid: b.inputs.ownerUid,
                        })
                    )
                    .addStep("provisionCert", INTERNAL, "provisionProxyCert", c =>
                            c.register({
                                certName: certManagerSecretName,
                                secretName: certManagerSecretName,
                                issuerName,
                                issuerKind,
                                issuerGroup,
                                dnsNames: expr.recordToString(tlsDnsNames),
                                duration: tlsDuration,
                                renewBefore: tlsRenewBefore,
                                ownerName: b.inputs.proxyName,
                                ownerUid: b.inputs.ownerUid,
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
                .addStep("waitForKafkaAuthSecret", ResourceManagement, "waitForSecretKey", c =>
                        c.register({
                            secretName: expr.getLoose(kafkaConfig, "secretName"),
                            secretKey: expr.literal("password"),
                        }),
                    {when: {templateExp: shouldUseScramAuth}}
                )
                .addStepGroup(g => g
                    .addStep("deployProxyPodDisruptionBudget", INTERNAL, "deployProxyPodDisruptionBudget", c =>
                            c.register({
                                proxyName: expr.get(expr.deserializeRecord(b.inputs.proxyConfig), "name"),
                                minPodReplicas: b.inputs.minPodReplicas,
                                ownerUid: b.inputs.ownerUid,
                                sourceK8sLabel: b.inputs.sourceK8sLabel,
                            })
                    )
                    .addStep("deployProxyNoTls", INTERNAL, "deployProxyDeployment", c =>
                            c.register({
                                ...selectInputsForRegister(b, c),
                                proxyName: expr.get(expr.deserializeRecord(b.inputs.proxyConfig), "name"),
                                listenPort: b.inputs.listenPort,
                                podReplicas: b.inputs.podReplicas,
                                kafkaAuthConfigMapName,
                                kafkaAuthType: effectiveKafkaAuthType,
                                kafkaSecretName: expr.ternary(
                                    shouldUseScramAuth,
                                    expr.getLoose(kafkaConfig, "secretName"),
                                    expr.literal("empty")
                                ),
                                kafkaCaSecretName: expr.ternary(
                                    shouldUseScramAuth,
                                    expr.getLoose(kafkaConfig, "caSecretName"),
                                    expr.literal("empty")
                                ),
                                resources: expr.serialize(expr.get(proxyOpts, "resources")),
                                sslTrustCertPem: expr.dig(proxyOpts, ["sslTrustCertPem"], ""),
                                fileSourceVolumes: expr.serialize(expr.dig(proxyOpts, ["fileSourceVolumes"], [])),
                                fileSourceVolumeMounts: expr.serialize(expr.dig(proxyOpts, ["fileSourceVolumeMounts"], [])),
                                ownerUid: b.inputs.ownerUid,
                                jsonConfig: expr.asString(expr.serialize(
                                    makeProxyParamsDict(
                                        b.inputs.proxyConfig,
                                        b.inputs.resolvedKafkaConnection,
                                        b.inputs.resolvedKafkaListenerName,
                                        b.inputs.resolvedKafkaAuthType
                                    )
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
                                kafkaSecretName: expr.ternary(
                                    shouldUseScramAuth,
                                    expr.getLoose(kafkaConfig, "secretName"),
                                    expr.literal("empty")
                                ),
                                kafkaCaSecretName: expr.ternary(
                                    shouldUseScramAuth,
                                    expr.getLoose(kafkaConfig, "caSecretName"),
                                    expr.literal("empty")
                                ),
                                resources: expr.serialize(expr.get(proxyOpts, "resources")),
                                tlsSecretName: tlsSecretName,
                                sslTrustCertPem: expr.dig(proxyOpts, ["sslTrustCertPem"], ""),
                                fileSourceVolumes: expr.serialize(expr.dig(proxyOpts, ["fileSourceVolumes"], [])),
                                fileSourceVolumeMounts: expr.serialize(expr.dig(proxyOpts, ["fileSourceVolumeMounts"], [])),
                                ownerUid: b.inputs.ownerUid,
                                jsonConfig: expr.asString(expr.serialize(
                                    expr.mergeDicts(
                                        makeProxyParamsDict(
                                            b.inputs.proxyConfig,
                                            b.inputs.resolvedKafkaConnection,
                                            b.inputs.resolvedKafkaListenerName,
                                            b.inputs.resolvedKafkaAuthType
                                        ),
                                        expr.makeDict({
                                            sslCertChainFile: expr.literal("/etc/proxy-tls/tls.crt"),
                                            sslKeyFile: expr.literal("/etc/proxy-tls/tls.key"),
                                        })
                                    )
                                )),
                            }),
                        {when: {templateExp: hasTls}}
                    )
                )
                .addStep("waitForProxyEndpointReady", INTERNAL, "waitForProxyEndpointReady", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        proxyName: b.inputs.proxyName,
                        listenPort: b.inputs.listenPort,
                        serviceType,
                    })
                )
                .addStep("patchCaptureProxyReady", ResourceManagement, "patchCaptureProxyReady", c =>
                    c.register({
                        resourceName: b.inputs.proxyName,
                        phase: expr.literal("Ready"),
                        configChecksum: b.inputs.configChecksum,
                        checksumForSnapshot: b.inputs.checksumForSnapshot,
                        checksumForReplayer: b.inputs.checksumForReplayer,
                        serviceEndpoint: c.steps.waitForProxyEndpointReady.outputs.serviceEndpoint,
                        loadBalancerEndpoint: c.steps.waitForProxyEndpointReady.outputs.loadBalancerEndpoint,
                    })
                );
        })
        .addExpressionOutput("serviceEndpoint", c => c.steps.waitForProxyEndpointReady.outputs.serviceEndpoint)
        .addExpressionOutput("loadBalancerEndpoint", c => c.steps.waitForProxyEndpointReady.outputs.loadBalancerEndpoint)
    )

    .addTemplate("reconcileCaptureTopicAndProxy", t => t
        .addRequiredInput("proxyConfig", typeToken<z.infer<typeof DENORMALIZED_PROXY_CONFIG>>())
        .addRequiredInput("kafkaClusterName", typeToken<string>())
        .addRequiredInput("kafkaTopicName", typeToken<string>())
        .addRequiredInput("proxyName", typeToken<string>())
        .addRequiredInput("topicCrName", typeToken<string>())
        .addRequiredInput("ownerUid", typeToken<string>())
        .addRequiredInput("configChecksum", typeToken<string>())
        .addRequiredInput("topicConfigChecksum", typeToken<string>())
        .addRequiredInput("checksumForSnapshot", typeToken<string>())
        .addRequiredInput("checksumForReplayer", typeToken<string>())
        .addRequiredInput("listenPort", typeToken<number>())
        .addInputsFromRecord(SCALABLE_WORKLOAD_INPUTS)
        .addRequiredInput("topicPartitions", typeToken<number>())
        .addRequiredInput("topicReplicas", typeToken<number>())
        .addRequiredInput("topicConfig", typeToken<Serialized<Record<string, any>>>())
        .addRequiredInput("kafkaClusterOwnerUid", typeToken<string>())
        .addRequiredInput("sourceK8sLabel", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole", "CaptureProxy"]))

        .addSteps(b => {
            const managedByWorkflow = expr.dig(
                expr.deserializeRecord(b.inputs.proxyConfig),
                ["kafkaConfig", "managedByWorkflow"],
                false
            );

            return b
            .addStep("reconcileCapturedTrafficResource", ResourceManagement, "reconcileCapturedTrafficResource", c =>
                c.register({
                    topicCrName: b.inputs.topicCrName,
                    kafkaClusterName: b.inputs.kafkaClusterName,
                    kafkaTopicName: b.inputs.kafkaTopicName,
                    sourceLabel: b.inputs.sourceK8sLabel,
                    partitions: b.inputs.topicPartitions,
                    replicas: b.inputs.topicReplicas,
                    topicConfig: b.inputs.topicConfig,
                    configChecksum: b.inputs.topicConfigChecksum,
                    retryGateName: expr.concat(expr.literal("capturedtraffic."), b.inputs.topicCrName, expr.literal(".vapretry")),
                    retryGroupName_view: expr.concat(expr.literal("CapturedTraffic: "), b.inputs.topicCrName),
                })
            )
            .addStep("waitForKafkaCluster", SetupKafka, "waitForKafkaCluster", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    resourceName: b.inputs.kafkaClusterName,
                }),
                { when: c => ({templateExp: expr.and(
                    checksumNotDone(c.reconcileCapturedTrafficResource.outputs.currentConfigChecksum, b.inputs.topicConfigChecksum),
                    managedByWorkflow
                )}) }
            )
            .addStep("createKafkaTopic", SetupKafka, "createKafkaTopic", c =>
                c.register({
                    clusterName: b.inputs.kafkaClusterName,
                    topicName: b.inputs.kafkaTopicName,
                    migrationRunNumber: t.inputs.workflowParameters.migrationRunNumber,
                    ownerUid: b.inputs.kafkaClusterOwnerUid,
                    sourceLabel: b.inputs.sourceK8sLabel,
                    partitions: b.inputs.topicPartitions,
                    replicas: b.inputs.topicReplicas,
                    topicConfig: b.inputs.topicConfig,
                }),
                { when: c => ({templateExp: expr.and(
                    checksumNotDone(c.reconcileCapturedTrafficResource.outputs.currentConfigChecksum, b.inputs.topicConfigChecksum),
                    managedByWorkflow
                )}) }
            )
            .addStep("waitForKafkaTopicReady", ResourceManagement, "waitForKafkaTopicReady", c =>
                    c.register({
                        topicName: b.inputs.kafkaTopicName,
                    }),
                { when: c => ({templateExp: expr.and(
                    checksumNotDone(c.reconcileCapturedTrafficResource.outputs.currentConfigChecksum, b.inputs.topicConfigChecksum),
                    managedByWorkflow
                )}) }
            )
            .addStep("patchCapturedTrafficReady", ResourceManagement, "patchCapturedTrafficReady", c =>
                c.register({
                    resourceName: b.inputs.topicCrName,
                    phase: expr.literal("Ready"),
                    configChecksum: b.inputs.topicConfigChecksum,
                    checksumForSnapshot: b.inputs.checksumForSnapshot,
                }),
                { when: c => ({templateExp: checksumNotDone(
                    c.reconcileCapturedTrafficResource.outputs.currentConfigChecksum,
                    b.inputs.topicConfigChecksum
                )}) }
            )
            .addStep("reconcileCaptureProxyResource", ResourceManagement, "reconcileCaptureProxyResource", c =>
                c.register({
                    proxyConfig: b.inputs.proxyConfig,
                    proxyName: b.inputs.proxyName,
                    topicCrName: b.inputs.topicCrName,
                    configChecksum: b.inputs.configChecksum,
                    retryGateName: expr.concat(expr.literal("captureproxy."), b.inputs.proxyName, expr.literal(".vapretry")),
                    retryGroupName_view: expr.concat(expr.literal("CaptureProxy: "), b.inputs.proxyName),
                })
            )
            .addStep("readKafkaConnectionProfile", ResourceManagement, "readKafkaConnectionProfile", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    resourceName: b.inputs.kafkaClusterName,
                }),
                { when: c => ({templateExp: expr.and(
                    checksumNotDone(c.reconcileCaptureProxyResource.outputs.currentConfigChecksum, b.inputs.configChecksum),
                    managedByWorkflow
                )}) }
            )
            .addStep("patchCaptureProxyPending", ResourceManagement, "patchCaptureProxyPending", c =>
                c.register({
                    resourceName: b.inputs.proxyName,
                    phase: expr.literal("Pending"),
                }),
                { when: c => ({templateExp: checksumNotDone(
                    c.reconcileCaptureProxyResource.outputs.currentConfigChecksum,
                    b.inputs.configChecksum
                )}) }
            )
            .addStep("setupProxy", INTERNAL, "setupProxy", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    proxyConfig: b.inputs.proxyConfig,
                    kafkaClusterName: b.inputs.kafkaClusterName,
                    proxyName: b.inputs.proxyName,
                    ownerUid: b.inputs.ownerUid,
                    listenPort: b.inputs.listenPort,
                    podReplicas: b.inputs.podReplicas,
                    minPodReplicas: b.inputs.minPodReplicas,
                    configChecksum: b.inputs.configChecksum,
                    checksumForSnapshot: b.inputs.checksumForSnapshot,
                    checksumForReplayer: b.inputs.checksumForReplayer,
                    resolvedKafkaConnection: c.steps.readKafkaConnectionProfile.outputs.bootstrapServers,
                    resolvedKafkaListenerName: c.steps.readKafkaConnectionProfile.outputs.listenerName,
                    resolvedKafkaAuthType: c.steps.readKafkaConnectionProfile.outputs.authType,
                }),
                {
                    when: c => ({templateExp: expr.and(
                        checksumNotDone(c.reconcileCaptureProxyResource.outputs.currentConfigChecksum, b.inputs.configChecksum),
                        managedByWorkflow
                    )}),
                    continueOn: {failed: true}
                }
            )
            .addStep("setupProxyWithConfiguredKafka", INTERNAL, "setupProxy", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    proxyConfig: b.inputs.proxyConfig,
                    kafkaClusterName: b.inputs.kafkaClusterName,
                    proxyName: b.inputs.proxyName,
                    ownerUid: b.inputs.ownerUid,
                    listenPort: b.inputs.listenPort,
                    podReplicas: b.inputs.podReplicas,
                    minPodReplicas: b.inputs.minPodReplicas,
                    configChecksum: b.inputs.configChecksum,
                    checksumForSnapshot: b.inputs.checksumForSnapshot,
                    checksumForReplayer: b.inputs.checksumForReplayer,
                }),
                {
                    when: c => ({templateExp: expr.and(
                        checksumNotDone(c.reconcileCaptureProxyResource.outputs.currentConfigChecksum, b.inputs.configChecksum),
                        expr.not(managedByWorkflow)
                    )}),
                    continueOn: {failed: true}
                }
            )
            .addStep("patchCaptureProxyError", ResourceManagement, "patchCaptureProxyError", c =>
                c.register({
                    resourceName: b.inputs.proxyName,
                    phase: expr.literal("Error"),
                }),
                {when: c => ({templateExp: expr.or(
                    expr.equals(c.setupProxy.status, "Failed"),
                    expr.equals(c.setupProxyWithConfiguredKafka.status, "Failed")
                )})}
            );
        })
    )


    .getFullScope();
