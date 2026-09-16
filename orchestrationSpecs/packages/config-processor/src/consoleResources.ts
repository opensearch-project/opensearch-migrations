import {
    ARGO_MIGRATION_CONFIG_PRE_ENRICH,
} from "@opensearch-migrations/schemas";
import {z} from "zod";
import type {NormalizedUserConfig} from "./migrationConfigTransformer";
import type {ResolvedMigrationResources, ResolvedParameterProvenanceMap} from "./resolvedMigrationResources";
import {CLUSTER_CLIENT_DISPLAY_FIELDS, KAFKA_CONFIG_DISPLAY_FIELDS} from "./resourceDisplayFields";

type WorkflowConfig = z.infer<typeof ARGO_MIGRATION_CONFIG_PRE_ENRICH>;
type SourceConfig = WorkflowConfig["proxies"][number]["sourceConfig"];
type TargetConfig = WorkflowConfig["trafficReplays"][number]["toTarget"];
type KafkaClientConfig = WorkflowConfig["proxies"][number]["kafkaConfig"];
type ProxyConfig = WorkflowConfig["proxies"][number];

export interface ConsoleResourceConsumer {
    kind: string;
    name: string;
    role?: string;
    configChecksum?: string;
}

export interface ConsoleClusterResource {
    refName: string;
    aliases: string[];
    clientConfig: Record<string, unknown>;
    editPath?: string[];
    displayFields?: string[];
    parameterProvenance?: ResolvedParameterProvenanceMap;
    consumers?: ConsoleResourceConsumer[];
    source?: "config" | "migrationRun";
}

export interface ConsoleSourceResource extends ConsoleClusterResource {
    repositories?: ConsoleRepositoryResource[];
    proxy?: {
        refName: string;
        k8sName: string;
        aliases: string[];
        clientConfig: Record<string, unknown>;
    };
}

export interface ConsoleSnapshotReference {
    refName: string;
    editPath: string[];
    generated: boolean;
    snapshotName?: string;
    snapshotPrefix?: string;
}

export interface ConsoleRepositoryResource {
    refName: string;
    provider: "s3" | "gcs";
    editPath: string[];
    clientConfig: Record<string, unknown>;
    snapshots: ConsoleSnapshotReference[];
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
    displayFields?: string[];
    parameterProvenance?: ResolvedParameterProvenanceMap;
    consumers?: ConsoleResourceConsumer[];
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

function clusterClientConfig(cluster: {
    endpoint?: string;
    version?: string;
    allowInsecure?: boolean;
    authConfig?: unknown;
}): Record<string, unknown> {
    return withDefinedValues({
        endpoint: cluster.endpoint,
        version: "version" in cluster ? cluster.version : undefined,
        allow_insecure: cluster.allowInsecure,
        ...mapAuthConfig(cluster.authConfig),
    });
}

function repositoryClientConfig(repoConfig: {
    repoPathUri: string;
    awsRegion?: string;
    endpoint?: string;
    useLocalStack?: boolean;
}): Record<string, unknown> {
    return withDefinedValues({
        repo_uri: repoConfig.repoPathUri,
        s3_region: repoConfig.repoPathUri.startsWith("s3://")
            ? repoConfig.awsRegion || undefined
            : undefined,
        endpoint: repoConfig.endpoint || undefined,
        use_local_stack: repoConfig.useLocalStack || undefined,
    });
}

function repositoriesFromSourceConfig(
    sourceName: string,
    sourceConfig: NormalizedUserConfig["sourceClusters"][string]
): ConsoleRepositoryResource[] {
    const snapshotInfo = sourceConfig.snapshotInfo;
    if (!snapshotInfo?.repos) {
        return [];
    }
    const snapshotsByRepo = new Map<string, ConsoleSnapshotReference[]>();
    for (const [snapshotName, snapshotConfig] of Object.entries(snapshotInfo.snapshots ?? {})) {
        let snapshotReference: ConsoleSnapshotReference;
        if ("createSnapshotConfig" in snapshotConfig.config) {
            snapshotReference = {
                refName: snapshotName,
                editPath: ["sourceClusters", sourceName, "snapshotInfo", "snapshots", snapshotName],
                generated: true,
                ...(snapshotConfig.config.createSnapshotConfig.snapshotPrefix
                    ? {snapshotPrefix: snapshotConfig.config.createSnapshotConfig.snapshotPrefix}
                    : {}),
            };
        } else {
            snapshotReference = {
                refName: snapshotName,
                editPath: ["sourceClusters", sourceName, "snapshotInfo", "snapshots", snapshotName],
                generated: false,
                snapshotName: snapshotConfig.config.externallyManagedSnapshotName,
            };
        }
        const snapshots = snapshotsByRepo.get(snapshotConfig.repoName) ?? [];
        snapshots.push(snapshotReference);
        snapshotsByRepo.set(snapshotConfig.repoName, snapshots);
    }
    return Object.entries(snapshotInfo.repos)
        .map(([repoName, repoConfig]) => ({
            refName: repoName,
            provider: repoConfig.repoPathUri.startsWith("gs://") ? "gcs" as const : "s3" as const,
            editPath: ["sourceClusters", sourceName, "snapshotInfo", "repos", repoName],
            clientConfig: repositoryClientConfig(repoConfig),
            snapshots: (snapshotsByRepo.get(repoName) ?? [])
                .sort((left, right) => left.refName.localeCompare(right.refName)),
        }))
        .sort((left, right) => left.refName.localeCompare(right.refName));
}

function configuredSources(userConfig: NormalizedUserConfig): ConsoleSourceResource[] {
    return Object.entries(userConfig.sourceClusters)
        .map(([sourceName, sourceConfig]) => ({
            refName: sourceName,
            aliases: [sourceName],
            clientConfig: clusterClientConfig(sourceConfig),
            displayFields: [...CLUSTER_CLIENT_DISPLAY_FIELDS],
            editPath: ["sourceClusters", sourceName],
            repositories: repositoriesFromSourceConfig(sourceName, sourceConfig),
        }))
        .sort((left, right) => left.refName.localeCompare(right.refName));
}

function configuredTargets(userConfig: NormalizedUserConfig): ConsoleClusterResource[] {
    return Object.entries(userConfig.targetClusters)
        .map(([targetName, targetConfig]) => ({
            refName: targetName,
            aliases: [targetName],
            clientConfig: clusterClientConfig(targetConfig),
            displayFields: [...CLUSTER_CLIENT_DISPLAY_FIELDS],
            editPath: ["targetClusters", targetName],
        }))
        .sort((left, right) => left.refName.localeCompare(right.refName));
}

function mergeConfiguredSources(
    runtimeSources: ConsoleSourceResource[],
    userConfig?: NormalizedUserConfig
): ConsoleSourceResource[] {
    if (!userConfig) {
        return runtimeSources;
    }
    const byName = new Map(runtimeSources.map(source => [source.refName, source]));
    for (const configured of configuredSources(userConfig)) {
        const runtime = byName.get(configured.refName);
        byName.set(configured.refName, runtime
            ? {
                ...runtime,
                editPath: configured.editPath,
                repositories: configured.repositories,
            }
            : configured);
    }
    return [...byName.values()].sort((left, right) => left.refName.localeCompare(right.refName));
}

function mergeConfiguredTargets(
    runtimeTargets: ConsoleClusterResource[],
    userConfig?: NormalizedUserConfig
): ConsoleClusterResource[] {
    if (!userConfig) {
        return runtimeTargets;
    }
    const byName = new Map(runtimeTargets.map(target => [target.refName, target]));
    for (const configured of configuredTargets(userConfig)) {
        const runtime = byName.get(configured.refName);
        byName.set(configured.refName, runtime
            ? {...runtime, editPath: configured.editPath}
            : configured);
    }
    return [...byName.values()].sort((left, right) => left.refName.localeCompare(right.refName));
}

function proxyClientConfig(
    sourceConfig: SourceConfig,
    proxyConfig: ProxyConfig["proxyConfig"],
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
    const clientAuth = proxyConfig.tls && "clientAuth" in proxyConfig.tls
        ? proxyConfig.tls.clientAuth
        : undefined;
    if (clientAuth?.consoleClientSecretName) {
        result.client_cert = {k8s_secret_name: clientAuth.consoleClientSecretName};
    }
    return result;
}

function consumer(kind: string, name: string, role: string, configChecksum?: string): ConsoleResourceConsumer {
    return {
        kind,
        name,
        role,
        ...(configChecksum ? {configChecksum} : {}),
    };
}

function mergeConsumers(
    left: ConsoleResourceConsumer[] | undefined,
    right: ConsoleResourceConsumer[] | undefined
): ConsoleResourceConsumer[] | undefined {
    const result = new Map<string, ConsoleResourceConsumer>();
    for (const item of [...(left ?? []), ...(right ?? [])]) {
        result.set([item.kind, item.name, item.role ?? ""].join(":"), item);
    }
    return result.size > 0
        ? [...result.values()].sort((a, b) =>
            [a.kind, a.name, a.role ?? ""].join(":").localeCompare([b.kind, b.name, b.role ?? ""].join(":")))
        : undefined;
}

function uniqueByRef<T extends {refName: string; proxy?: unknown; consumers?: ConsoleResourceConsumer[]}>(items: T[]): T[] {
    const byRef = new Map<string, T>();
    for (const item of items) {
        if (!byRef.has(item.refName)) {
            byRef.set(item.refName, item);
            continue;
        }
        const previous = byRef.get(item.refName)!;
        byRef.set(item.refName, {
            ...previous,
            ...(!previous.proxy && item.proxy ? {proxy: item.proxy} : {}),
            consumers: mergeConsumers(previous.consumers, item.consumers),
        });
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

function kafkaResource(
    kafkaConfig: KafkaClientConfig,
    consumers: ConsoleResourceConsumer[] = []
): ConsoleKafkaResource {
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
            displayFields: [...KAFKA_CONFIG_DISPLAY_FIELDS],
            ...(consumers.length > 0 ? {consumers} : {}),
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
        displayFields: [...KAFKA_CONFIG_DISPLAY_FIELDS],
        ...(consumers.length > 0 ? {consumers} : {}),
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
            displayFields: [...CLUSTER_CLIENT_DISPLAY_FIELDS],
            consumers: [consumer("CaptureProxy", proxy.name, "capture source", proxy.configChecksum)],
            proxy: {
                refName: proxy.name,
                k8sName: proxy.name,
                aliases: [
                    proxy.name,
                    `captureproxy.${proxy.name}`,
                ],
                clientConfig: proxyClientConfig(
                    sourceConfig,
                    proxy.proxyConfig,
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
            displayFields: [...CLUSTER_CLIENT_DISPLAY_FIELDS],
            consumers: snapshot.createSnapshotConfig.map(item =>
                consumer("DataSnapshot", `${sourceConfig.label}-${item.label}`, "snapshot source", item.configChecksum)),
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
                displayFields: [...CLUSTER_CLIENT_DISPLAY_FIELDS],
                consumers: [consumer(
                    "SnapshotMigration",
                    [migration.sourceLabel, migration.targetConfig.label, migration.label, migration.migrationLabel].join("-"),
                    "migration source",
                    migration.configChecksum
                )],
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
            displayFields: [...CLUSTER_CLIENT_DISPLAY_FIELDS],
            consumers: [consumer(
                "SnapshotMigration",
                [migration.sourceLabel, migration.targetConfig.label, migration.label, migration.migrationLabel].join("-"),
                "migration target",
                migration.configChecksum
            )],
        });
    }
    for (const replay of workflowConfig.trafficReplays ?? []) {
        targets.push({
            refName: replay.toTarget.label,
            aliases: [replay.toTarget.label],
            clientConfig: clusterClientConfig(replay.toTarget),
            displayFields: [...CLUSTER_CLIENT_DISPLAY_FIELDS],
            consumers: [consumer("TrafficReplay", replay.name, "replay target", replay.configChecksum)],
        });
    }
    return uniqueByRef(targets);
}

function kafkasFromWorkflowConfig(workflowConfig: WorkflowConfig): ConsoleKafkaResource[] {
    const kafkas: ConsoleKafkaResource[] = [];
    for (const proxy of workflowConfig.proxies ?? []) {
        kafkas.push(kafkaResource(proxy.kafkaConfig, [
            consumer("CaptureProxy", proxy.name, "capture kafka", proxy.configChecksum),
            consumer("CapturedTraffic", `${proxy.name}-topic`, "capture topic", proxy.topicConfigChecksum),
        ]));
    }
    for (const replay of workflowConfig.trafficReplays ?? []) {
        kafkas.push(kafkaResource(replay.kafkaConfig, [
            consumer("TrafficReplay", replay.name, "replay kafka"),
        ]));
    }
    for (const kafkaCluster of workflowConfig.kafkaClusters ?? []) {
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
            displayFields: [...KAFKA_CONFIG_DISPLAY_FIELDS],
            consumers: [consumer("KafkaCluster", kafkaCluster.name, "managed kafka", kafkaCluster.configChecksum)],
        });
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
    source: "config" | "migrationRun" = "config",
    userConfig?: NormalizedUserConfig
): ConsoleResources {
    const withSource = <T extends {source?: "config" | "migrationRun"}>(items: T[]) =>
        items.map(item => ({...item, source}));
    return {
        formatVersion: 1,
        ...(workflowName ? {workflowName} : {}),
        sources: withSource(mergeConfiguredSources(sourcesFromWorkflowConfig(workflowConfig), userConfig)),
        targets: withSource(mergeConfiguredTargets(targetsFromWorkflowConfig(workflowConfig), userConfig)),
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
    const workflowConfig = normalizeHistoricalWorkflowConfig(resolvedConfig.workflowConfig);
    const parsed = ARGO_MIGRATION_CONFIG_PRE_ENRICH.safeParse(workflowConfig);
    return buildConsoleResources(
        // MigrationRun snapshots were validated when they were submitted. A
        // newer console schema may reject a nested value that does not affect
        // this projection, but that must not hide the historical resources.
        parsed.success ? parsed.data : workflowConfig as WorkflowConfig,
        resolvedConfig.workflowName,
        "migrationRun"
    );
}

function normalizeHistoricalWorkflowConfig(workflowConfig: unknown): unknown {
    const normalize = (value: unknown, parentKey?: string): unknown => {
        if (Array.isArray(value)) {
            return value.map(child => normalize(child));
        }
        if (typeof value !== "object" || value === null) {
            return value;
        }
        const normalized = Object.fromEntries(
            Object.entries(value).map(([key, child]) => [key, normalize(child, key)])
        );
        if (
            parentKey?.endsWith("ConnectionIdentity")
            && normalized.solrContextPath === undefined
        ) {
            normalized.solrContextPath = "";
        }
        return normalized;
    };

    const normalized = normalize(workflowConfig);
    if (typeof normalized !== "object" || normalized === null || Array.isArray(normalized)) {
        return normalized;
    }
    const normalizedObject = normalized as Record<string, unknown>;
    return {
        ...normalizedObject,
        requireBeginApproval: normalizedObject.requireBeginApproval ?? false,
    };
}
