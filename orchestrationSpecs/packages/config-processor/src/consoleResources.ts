import {
    ARGO_MIGRATION_CONFIG_PRE_ENRICH,
} from "@opensearch-migrations/schemas";
import {z} from "zod";
import {ResolvedMigrationResources} from "./resolvedMigrationResources";

type WorkflowConfig = z.infer<typeof ARGO_MIGRATION_CONFIG_PRE_ENRICH>;
type SourceConfig = WorkflowConfig["proxies"][number]["sourceConfig"];
type TargetConfig = WorkflowConfig["trafficReplays"][number]["toTarget"];
type KafkaClientConfig = WorkflowConfig["proxies"][number]["kafkaConfig"];
type ProxyConfig = WorkflowConfig["proxies"][number];

export interface ConsoleClusterResource {
    refName: string;
    aliases: string[];
    clientConfig: Record<string, unknown>;
    source?: "config" | "migrationRun";
}

export interface ConsoleSourceResource extends ConsoleClusterResource {
    proxy?: {
        refName: string;
        k8sName: string;
        aliases: string[];
        clientConfig: Record<string, unknown>;
    };
}

export interface ConsoleKafkaResource {
    refName: string;
    k8sName?: string;
    aliases: string[];
    runtime:
        | {
            type: "strimzi";
            clusterName: string;
            authType: string;
            listenerName: string;
            usernameSecret?: string;
            caSecret?: string;
            kafkaUserName?: string;
        }
        | {
            type: "direct";
            clientConfig: Record<string, unknown>;
            secretName?: string;
            caSecretName?: string;
            kafkaUserName?: string;
        };
    source?: "config" | "migrationRun";
}

export interface ConsoleConsumerGroupResource {
    name: string;
    targetRef: string;
    kafkaRef: string;
    replayRef: string;
}

export interface ConsoleResources {
    formatVersion: 1;
    workflowName?: string;
    sources: ConsoleSourceResource[];
    targets: ConsoleClusterResource[];
    kafkas: ConsoleKafkaResource[];
    consumerGroups: ConsoleConsumerGroupResource[];
}

const SIGV4_SIGNING_ENDPOINT_KEY = "sigv4_signing_endpoint";

function withDefinedValues(source: Record<string, unknown>): Record<string, unknown> {
    return Object.fromEntries(
        Object.entries(source).filter(([, value]) => value !== undefined)
    );
}

function mapAuthConfig(authConfig: unknown): Record<string, unknown> {
    if (typeof authConfig !== "object" || authConfig === null) {
        return {no_auth: null};
    }

    const auth = authConfig as Record<string, any>;
    if ("basic" in auth) {
        const basic = auth.basic as Record<string, unknown>;
        if ("secretName" in basic) {
            return {basic_auth: {k8s_secret_name: basic.secretName}};
        }
        if ("secretArn" in basic) {
            return {basic_auth: {user_secret_arn: basic.secretArn}};
        }
        return {
            basic_auth: withDefinedValues({
                username: basic.username,
                password: basic.password,
            }),
        };
    }
    if ("sigv4" in auth) {
        return {sigv4: auth.sigv4 ?? null};
    }
    if ("mtls" in auth) {
        return {mtls_auth: auth.mtls ?? null};
    }
    return {no_auth: null};
}

function clusterClientConfig(cluster: SourceConfig | TargetConfig): Record<string, unknown> {
    return withDefinedValues({
        endpoint: cluster.endpoint,
        version: "version" in cluster ? cluster.version : undefined,
        allow_insecure: cluster.allowInsecure,
        ...mapAuthConfig(cluster.authConfig),
    });
}

function proxyClientConfig(
    sourceConfig: SourceConfig,
    proxyName: string,
    listenPort: number,
    hasTls: boolean,
    allowInsecure: boolean
): Record<string, unknown> {
    const baseConfig = clusterClientConfig(sourceConfig);
    const result: Record<string, unknown> = {
        ...baseConfig,
        endpoint: `${hasTls ? "https" : "http"}://${proxyName}:${listenPort}`,
        allow_insecure: allowInsecure,
    };
    if ("sigv4" in baseConfig) {
        result[SIGV4_SIGNING_ENDPOINT_KEY] = sourceConfig.endpoint;
    }
    return result;
}

function uniqueByRef<T extends {refName: string; proxy?: unknown}>(items: T[]): T[] {
    const byRef = new Map<string, T>();
    for (const item of items) {
        if (!byRef.has(item.refName)) {
            byRef.set(item.refName, item);
        } else if (!byRef.get(item.refName)!.proxy && item.proxy) {
            byRef.set(item.refName, {...byRef.get(item.refName)!, proxy: item.proxy});
        }
    }
    return [...byRef.values()].sort((a, b) => a.refName.localeCompare(b.refName));
}

function directKafkaClientConfig(kafkaConfig: KafkaClientConfig): Record<string, unknown> {
    return withDefinedValues({
        broker_endpoints: kafkaConfig.kafkaConnection,
        ...(kafkaConfig.enableMSKAuth ? {msk: null} : {}),
        ...(!kafkaConfig.enableMSKAuth && kafkaConfig.authType === "none" ? {standard: null} : {}),
        ...(!kafkaConfig.enableMSKAuth && kafkaConfig.authType === "scram-sha-512" ? {
            scram: withDefinedValues({
                username: kafkaConfig.kafkaUserName || undefined,
            }),
        } : {}),
    });
}

function kafkaResource(kafkaConfig: KafkaClientConfig): ConsoleKafkaResource {
    const refName = kafkaConfig.label;
    if (kafkaConfig.managedByWorkflow) {
        return {
            refName,
            k8sName: refName,
            aliases: [
                refName,
                `kafkacluster.${refName}`,
            ],
            runtime: {
                type: "strimzi",
                clusterName: refName,
                authType: kafkaConfig.authType ?? "scram-sha-512",
                listenerName: kafkaConfig.listenerName ?? "tls",
                usernameSecret: kafkaConfig.secretName || undefined,
                caSecret: kafkaConfig.caSecretName || undefined,
                kafkaUserName: kafkaConfig.kafkaUserName || undefined,
            },
        };
    }
    return {
        refName,
        aliases: [refName],
        runtime: {
            type: "direct",
            clientConfig: directKafkaClientConfig(kafkaConfig),
            secretName: kafkaConfig.secretName || undefined,
            caSecretName: kafkaConfig.caSecretName || undefined,
            kafkaUserName: kafkaConfig.kafkaUserName || undefined,
        },
    };
}

function sourcesFromWorkflowConfig(workflowConfig: WorkflowConfig): ConsoleSourceResource[] {
    const sources: ConsoleSourceResource[] = [];

    for (const proxy of workflowConfig.proxies ?? []) {
        const sourceConfig = proxy.sourceConfig;
        const hasTls = proxyHasTls(proxy);
        sources.push({
            refName: sourceConfig.label,
            aliases: [sourceConfig.label],
            clientConfig: clusterClientConfig(sourceConfig),
            proxy: {
                refName: proxy.name,
                k8sName: proxy.name,
                aliases: [
                    proxy.name,
                    `captureproxy.${proxy.name}`,
                ],
                clientConfig: proxyClientConfig(
                    sourceConfig,
                    proxy.name,
                    proxy.proxyConfig.listenPort,
                    hasTls,
                    hasTls
                ),
            },
        });
    }

    for (const snapshot of workflowConfig.snapshots ?? []) {
        const sourceConfig = snapshot.sourceConfig;
        sources.push({
            refName: sourceConfig.label,
            aliases: [sourceConfig.label],
            clientConfig: clusterClientConfig(sourceConfig),
            ...(sourceConfig.proxy ? {
                proxy: {
                    refName: sourceConfig.proxy.name ?? sourceConfig.label,
                    k8sName: sourceConfig.proxy.name ?? sourceConfig.label,
                    aliases: [sourceConfig.proxy.name ?? sourceConfig.label],
                    clientConfig: withDefinedValues({
                        ...clusterClientConfig(sourceConfig),
                        endpoint: sourceConfig.proxy.endpoint,
                        allow_insecure: sourceConfig.proxy.allowInsecure,
                    }),
                },
            } : {}),
        });
    }

    for (const migration of workflowConfig.snapshotMigrations ?? []) {
        if (migration.sourceEndpoint !== undefined) {
            sources.push({
                refName: migration.sourceLabel,
                aliases: [migration.sourceLabel],
                clientConfig: withDefinedValues({
                    endpoint: migration.sourceEndpoint,
                    version: migration.sourceVersion,
                    allow_insecure: migration.sourceAllowInsecure,
                    ...mapAuthConfig(migration.sourceAuth),
                }),
            });
        }
    }

    return uniqueByRef(sources);
}

function proxyHasTls(proxy: ProxyConfig): boolean {
    return typeof proxy.proxyConfig.tls === "object" && proxy.proxyConfig.tls !== null;
}

function targetsFromWorkflowConfig(workflowConfig: WorkflowConfig): ConsoleClusterResource[] {
    const targets: ConsoleClusterResource[] = [];
    for (const migration of workflowConfig.snapshotMigrations ?? []) {
        targets.push({
            refName: migration.targetConfig.label,
            aliases: [migration.targetConfig.label],
            clientConfig: clusterClientConfig(migration.targetConfig),
        });
    }
    for (const replay of workflowConfig.trafficReplays ?? []) {
        targets.push({
            refName: replay.toTarget.label,
            aliases: [replay.toTarget.label],
            clientConfig: clusterClientConfig(replay.toTarget),
        });
    }
    return uniqueByRef(targets);
}

function kafkasFromWorkflowConfig(workflowConfig: WorkflowConfig): ConsoleKafkaResource[] {
    const kafkas: ConsoleKafkaResource[] = [];
    for (const proxy of workflowConfig.proxies ?? []) {
        kafkas.push(kafkaResource(proxy.kafkaConfig));
    }
    for (const replay of workflowConfig.trafficReplays ?? []) {
        kafkas.push(kafkaResource(replay.kafkaConfig));
    }
    for (const kafkaCluster of workflowConfig.kafkaClusters ?? []) {
        if (!kafkas.some(kafka => kafka.refName === kafkaCluster.name)) {
            const authType = kafkaCluster.config.auth?.type ?? "scram-sha-512";
            const listenerName = authType === "scram-sha-512" ? "tls" : "plain";
            kafkas.push({
                refName: kafkaCluster.name,
                k8sName: kafkaCluster.name,
                aliases: [
                    kafkaCluster.name,
                    `kafkacluster.${kafkaCluster.name}`,
                ],
                runtime: {
                    type: "strimzi",
                    clusterName: kafkaCluster.name,
                    authType,
                    listenerName,
                    usernameSecret: authType === "scram-sha-512" ? `${kafkaCluster.name}-migration-app` : undefined,
                    caSecret: authType === "scram-sha-512" ? `${kafkaCluster.name}-cluster-ca-cert` : undefined,
                    kafkaUserName: authType === "scram-sha-512" ? `${kafkaCluster.name}-migration-app` : undefined,
                },
            });
        }
    }
    return uniqueByRef(kafkas);
}

function consumerGroupsFromWorkflowConfig(workflowConfig: WorkflowConfig): ConsoleConsumerGroupResource[] {
    const byName = new Map<string, ConsoleConsumerGroupResource>();
    for (const replay of workflowConfig.trafficReplays ?? []) {
        const name = `replayer-${replay.toTarget.label}`;
        if (!byName.has(name)) {
            byName.set(name, {
                name,
                targetRef: replay.toTarget.label,
                kafkaRef: replay.kafkaClusterName,
                replayRef: replay.name,
            });
        }
    }
    return [...byName.values()].sort((a, b) => a.name.localeCompare(b.name));
}

export function buildConsoleResources(
    workflowConfig: WorkflowConfig,
    workflowName?: string,
    source: "config" | "migrationRun" = "config"
): ConsoleResources {
    const withSource = <T extends {source?: "config" | "migrationRun"}>(items: T[]) =>
        items.map(item => ({...item, source}));
    return {
        formatVersion: 1,
        ...(workflowName ? {workflowName} : {}),
        sources: withSource(sourcesFromWorkflowConfig(workflowConfig)),
        targets: withSource(targetsFromWorkflowConfig(workflowConfig)),
        kafkas: withSource(kafkasFromWorkflowConfig(workflowConfig)),
        consumerGroups: consumerGroupsFromWorkflowConfig(workflowConfig),
    };
}

export function buildConsoleResourcesFromResolvedConfig(
    resolvedConfig: ResolvedMigrationResources
): ConsoleResources {
    if (!resolvedConfig.workflowConfig) {
        throw new Error("Resolved config does not include a strict workflowConfig.");
    }
    return buildConsoleResources(
        ARGO_MIGRATION_CONFIG_PRE_ENRICH.parse(resolvedConfig.workflowConfig),
        resolvedConfig.workflowName,
        "migrationRun"
    );
}
