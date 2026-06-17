import {
    ARGO_MIGRATION_CONFIG_PRE_ENRICH,
    collectProjectedFields,
    DEFAULT_KAFKA_TOPIC_SPEC_OVERRIDES,
    ProjectedField,
} from "@opensearch-migrations/schemas";
import {createHash} from "crypto";
import {z} from "zod";
import {FILE_SOURCE_RUNTIME_FIELDS, fileSourceRefsForTrace} from "./fileSourceUtils";
import {KAFKA_VERSION, MigrationConfigTransformer} from "./migrationConfigTransformer";
import {validationForConfig} from "./editConfig";
import type {EditDiagnostic} from "./editConfig";
import type {ConsoleResources} from "./consoleResources";

type WorkflowConfig = z.infer<typeof ARGO_MIGRATION_CONFIG_PRE_ENRICH>;
type KafkaClusterConfig = NonNullable<WorkflowConfig["kafkaClusters"]>[number];
type ProxyConfig = WorkflowConfig["proxies"][number];
type S3TrafficLoaderConfig = WorkflowConfig["s3TrafficLoaders"][number];
type SnapshotConfig = WorkflowConfig["snapshots"][number];
type SnapshotItemConfig = SnapshotConfig["createSnapshotConfig"][number];
type SnapshotMigrationConfig = WorkflowConfig["snapshotMigrations"][number];
type ReplayConfig = WorkflowConfig["trafficReplays"][number];

export type ResolvedParameterPolicy = Pick<
    ProjectedField,
    "specPath" | "sourceSchema" | "sourcePath" | "changeRestriction" | "checksumFor" | "invariant"
>;

export interface ResolvedMigrationResource {
    apiVersion: string;
    kind: string;
    name: string;
    parameters: Record<string, unknown>;
    annotations?: Record<string, string>;
    parameterPolicies?: ResolvedParameterPolicy[];
    projectionComplete?: boolean;
    diagnostics?: EditDiagnostic[];
}

export interface ResolvedMigrationResources {
    formatVersion: 1;
    workflowName?: string;
    workflowConfig?: WorkflowConfig;
    resources: ResolvedMigrationResource[];
    projectionMode?: "strict" | "loose";
    projectionComplete?: boolean;
    validation?: {
        mode: "strict" | "loose";
        valid: boolean;
        errors: string[];
        diagnostics?: EditDiagnostic[];
    };
    consoleResources?: ConsoleResources;
}

export interface ResolvedMigrationResourcesOptions {
    includeParameterPolicies?: boolean;
}

export interface VapDryRunChange {
    path: string;
    previousValue?: unknown;
    pendingValue?: unknown;
    changeRestriction: "safe" | "gated" | "impossible";
    invariant?: "nonDecreasing";
    result: "allowed" | "approval-required" | "blocked";
    message: string;
}

export interface VapDryRunResult {
    kind: string;
    name?: string;
    allowed: boolean;
    changes: VapDryRunChange[];
}

const CRD_API_VERSION = "migrations.opensearch.org/v1alpha1";

function removeUndefined(value: unknown): unknown {
    if (Array.isArray(value)) {
        return value.map(removeUndefined);
    }
    if (typeof value !== "object" || value === null) {
        return value;
    }

    return Object.fromEntries(
        Object.entries(value)
            .filter(([, child]) => child !== undefined)
            .map(([key, child]) => [key, removeUndefined(child)])
    );
}

function setPath(target: Record<string, unknown>, path: string[], value: unknown) {
    let cursor: Record<string, unknown> = target;
    for (let i = 0; i < path.length; i++) {
        const key = path[i];
        const isLast = i === path.length - 1;
        if (isLast) {
            cursor[key] = value;
            return;
        }
        if (typeof cursor[key] !== "object" || cursor[key] === null || Array.isArray(cursor[key])) {
            cursor[key] = {};
        }
        cursor = cursor[key] as Record<string, unknown>;
    }
}

function hasPath(source: Record<string, unknown>, path: string[]): boolean {
    let cursor: unknown = source;
    for (const key of path) {
        if (typeof cursor !== "object" || cursor === null || Array.isArray(cursor) || !(key in cursor)) {
            return false;
        }
        cursor = (cursor as Record<string, unknown>)[key];
    }
    return true;
}

function getPath(source: Record<string, unknown>, path: string[]): unknown {
    let cursor: unknown = source;
    for (const key of path) {
        if (typeof cursor !== "object" || cursor === null || Array.isArray(cursor)) {
            return undefined;
        }
        cursor = (cursor as Record<string, unknown>)[key];
    }
    return cursor;
}

function stableEqual(left: unknown, right: unknown): boolean {
    return JSON.stringify(left) === JSON.stringify(right);
}

function prefixFields(prefix: string, value: Record<string, unknown> | undefined): Record<string, unknown> {
    if (!value) {
        return {};
    }
    return Object.fromEntries(
        Object.entries(value).map(([key, child]) => [
            `${prefix}${key.charAt(0).toUpperCase()}${key.slice(1)}`,
            child,
        ])
    );
}

const CAPTURE_PROXY_RESOURCE_OMITTED_FIELDS = [
    // Workflow/deployment bridge fields. The CaptureProxy CRD does not own these
    // until the resource controller grows file-source-aware mTLS support.
    ...FILE_SOURCE_RUNTIME_FIELDS,
    "sslTrustCertFile",
    "sslTrustCertPem",
    "sslTrustCertPemEnvVar",
    "requireClientAuth",
] as const;

const WORKFLOW_ONLY_FIELDS_ANNOTATION = "migrations.opensearch.org/workflow-only-fields";
const WORKFLOW_ONLY_HASH_ANNOTATION = "migrations.opensearch.org/workflow-only-hash";
const FILE_SOURCE_REFS_ANNOTATION = "migrations.opensearch.org/file-source-refs";

function omitFields(source: Record<string, unknown>, fields: readonly string[]): Record<string, unknown> {
    const result = {...source};
    for (const field of fields) {
        delete result[field];
    }
    return result;
}

function isNonEmptyTraceValue(value: unknown): boolean {
    if (value === undefined) {
        return false;
    }
    if (Array.isArray(value)) {
        return value.length > 0;
    }
    return true;
}

function pickFields(source: Record<string, unknown>, fields: readonly string[]): Record<string, unknown> {
    const result: Record<string, unknown> = {};
    for (const field of fields) {
        if (field in source && isNonEmptyTraceValue(source[field])) {
            result[field] = source[field];
        }
    }
    return result;
}

function traceHash(value: unknown) {
    return `sha256:${createHash("sha256").update(JSON.stringify(value)).digest("hex")}`;
}

function workflowOnlyTraceAnnotations(omittedFields: Record<string, unknown>) {
    const fields = Object.keys(omittedFields);
    if (fields.length === 0) {
        return undefined;
    }

    const annotations: Record<string, string> = {
        [WORKFLOW_ONLY_FIELDS_ANNOTATION]: fields.join(","),
        [WORKFLOW_ONLY_HASH_ANNOTATION]: traceHash(omittedFields),
    };
    const fileSourceRefs = fileSourceRefsForTrace(omittedFields);
    if (fileSourceRefs.length > 0) {
        annotations[FILE_SOURCE_REFS_ANNOTATION] = JSON.stringify(fileSourceRefs);
    }
    return annotations;
}

function resourcePolicies(kind: string, parameters: Record<string, unknown>): ResolvedParameterPolicy[] {
    return collectProjectedFields()
        .filter(field => field.resourceKind === kind && hasPath(parameters, field.specPath))
        .map(({specPath, sourceSchema, sourcePath, changeRestriction, checksumFor, invariant}) => ({
            specPath,
            sourceSchema,
            sourcePath,
            changeRestriction,
            checksumFor,
            invariant,
        }));
}

function resource(
    kind: string,
    name: string,
    parameters: Record<string, unknown>,
    options: ResolvedMigrationResourcesOptions = {},
    annotations?: Record<string, string>,
): ResolvedMigrationResource {
    const normalizedParameters = removeUndefined(parameters) as Record<string, unknown>;
    return {
        apiVersion: CRD_API_VERSION,
        kind,
        name,
        parameters: normalizedParameters,
        ...(annotations === undefined ? {} : {annotations}),
        ...(options.includeParameterPolicies
            ? {parameterPolicies: resourcePolicies(kind, normalizedParameters)}
            : {}),
    };
}

function kafkaClusterParameters(kafkaCluster: KafkaClusterConfig): Record<string, unknown> {
    const config = (kafkaCluster.config ?? {}) as Record<string, any>;
    const nodePool = config.nodePoolSpecOverrides ?? {};
    const storage = nodePool.storage ?? {};
    const parameters: Record<string, unknown> = {
        version: kafkaCluster.version,
    };
    setPath(parameters, ["auth", "type"], config.auth?.type);
    setPath(parameters, ["nodePool", "replicas"], nodePool.replicas);
    setPath(parameters, ["nodePool", "roles"], nodePool.roles);
    setPath(parameters, ["nodePool", "storage", "size"], storage.size);
    setPath(parameters, ["nodePool", "storage", "type"], storage.type);
    return parameters;
}

function capturedTrafficParameters(proxy: ProxyConfig): Record<string, unknown> {
    return {
        dependsOn: [proxy.kafkaConfig.label],
        kafkaClusterName: proxy.kafkaConfig.label,
        topicName: proxy.kafkaConfig.kafkaTopic,
        partitions: proxy.kafkaConfig.topicSpecOverrides?.partitions,
        replicas: proxy.kafkaConfig.topicSpecOverrides?.replicas,
        topicConfig: proxy.kafkaConfig.topicSpecOverrides?.config,
    };
}

function s3CapturedTrafficParameters(loader: S3TrafficLoaderConfig): Record<string, unknown> {
    return {
        dependsOn: [loader.kafkaConfig.label],
        kafkaClusterName: loader.kafkaConfig.label,
        topicName: loader.kafkaConfig.kafkaTopic,
        partitions: loader.kafkaConfig.topicSpecOverrides?.partitions,
        replicas: loader.kafkaConfig.topicSpecOverrides?.replicas,
        topicConfig: loader.kafkaConfig.topicSpecOverrides?.config,
        sourceKind: "s3",
        s3SourceUri: loader.s3Uri,
        loadStarted: true,
    };
}

function captureProxyParameters(proxy: ProxyConfig): Record<string, unknown> {
    return {
        ...omitFields(proxy.proxyConfig as Record<string, unknown>, CAPTURE_PROXY_RESOURCE_OMITTED_FIELDS),
        dependsOn: [`${proxy.name}-topic`],
    };
}

function captureProxyAnnotations(proxy: ProxyConfig) {
    const omittedFields = pickFields(
        proxy.proxyConfig as Record<string, unknown>,
        CAPTURE_PROXY_RESOURCE_OMITTED_FIELDS
    );
    return workflowOnlyTraceAnnotations(omittedFields);
}

function dataSnapshotParameters(item: SnapshotItemConfig): Record<string, unknown> {
    return {
        snapshotPrefix: item.snapshotPrefix,
        ...item.config,
        dependsOn: (item.dependsOnProxySetups ?? []).map(dep => dep.name),
    };
}

function snapshotMigrationParameters(migration: SnapshotMigrationConfig): Record<string, unknown> {
    const dataSnapshotResourceName =
        "dataSnapshotResourceName" in migration.snapshotNameResolution
            ? migration.snapshotNameResolution.dataSnapshotResourceName
            : undefined;
    return {
        ...prefixFields("metadataMigration", migration.metadataMigrationConfig as Record<string, unknown>),
        ...prefixFields("documentBackfill", migration.documentBackfillConfig as Record<string, unknown>),
        dependsOn: dataSnapshotResourceName ? [dataSnapshotResourceName] : [],
        migrationLabel: migration.migrationLabel,
        sourceVersion: migration.sourceVersion,
        sourceLabel: migration.sourceLabel,
        targetLabel: migration.targetConfig.label,
        snapshotLabel: migration.label,
    };
}

function trafficReplayParameters(replay: ReplayConfig): Record<string, unknown> {
    return {
        ...replay.replayerConfig,
        dependsOn: replay.dependsOn,
    };
}

export function buildResolvedMigrationResourceList(
    workflowConfig: WorkflowConfig,
    options: ResolvedMigrationResourcesOptions = {},
): ResolvedMigrationResource[] {
    const resources: ResolvedMigrationResource[] = [];

    for (const kafkaCluster of workflowConfig.kafkaClusters ?? []) {
        resources.push(resource("KafkaCluster", kafkaCluster.name, kafkaClusterParameters(kafkaCluster), options));
    }

    for (const proxy of workflowConfig.proxies ?? []) {
        resources.push(resource("CapturedTraffic", `${proxy.name}-topic`, capturedTrafficParameters(proxy), options));
        resources.push(resource(
            "CaptureProxy",
            proxy.name,
            captureProxyParameters(proxy),
            options,
            captureProxyAnnotations(proxy)
        ));
    }

    for (const loader of workflowConfig.s3TrafficLoaders ?? []) {
        resources.push(resource("CapturedTraffic", `${loader.name}-topic`, s3CapturedTrafficParameters(loader), options));
    }

    for (const snapshot of workflowConfig.snapshots ?? []) {
        for (const item of snapshot.createSnapshotConfig) {
            resources.push(resource(
                "DataSnapshot",
                `${snapshot.sourceConfig.label}-${item.label}`,
                dataSnapshotParameters(item),
                options,
            ));
        }
    }

    for (const migration of workflowConfig.snapshotMigrations ?? []) {
        resources.push(resource(
            "SnapshotMigration",
            [
                migration.sourceLabel,
                migration.targetConfig.label,
                migration.label,
                migration.migrationLabel,
            ].join("-"),
            snapshotMigrationParameters(migration),
            options,
        ));
    }

    for (const replay of workflowConfig.trafficReplays ?? []) {
        resources.push(resource("TrafficReplay", replay.name, trafficReplayParameters(replay), options));
    }

    return resources;
}

export function buildResolvedMigrationResources(
    workflowConfig: WorkflowConfig,
    workflowName?: string,
    options: ResolvedMigrationResourcesOptions = {},
): ResolvedMigrationResources {
    return {
        formatVersion: 1,
        ...(workflowName ? {workflowName} : {}),
        workflowConfig,
        resources: buildResolvedMigrationResourceList(workflowConfig, options),
    };
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

function asRecord(value: unknown): Record<string, unknown> {
    return isRecord(value) ? value : {};
}

function recordEntries(value: unknown): [string, Record<string, unknown>][] {
    if (!isRecord(value)) {
        return [];
    }
    return Object.entries(value).flatMap(([key, child]) =>
        isRecord(child) ? [[key, child] as [string, Record<string, unknown>]] : []
    );
}

function asString(value: unknown): string | undefined {
    return typeof value === "string" && value !== "" ? value : undefined;
}

function asBoolean(value: unknown): boolean | undefined {
    return typeof value === "boolean" ? value : undefined;
}

function startsWithPath(path: string[] | undefined, prefix: string[]): boolean {
    if (!path || path.length < prefix.length) {
        return false;
    }
    return prefix.every((part, index) => path[index] === part);
}

function diagnosticsForPrefixes(
    diagnostics: EditDiagnostic[] | undefined,
    prefixes: string[][],
): EditDiagnostic[] {
    const result = new Map<string, EditDiagnostic>();
    for (const diagnostic of diagnostics ?? []) {
        if (!prefixes.some(prefix => startsWithPath(diagnostic.path, prefix))) {
            continue;
        }
        result.set(
            `${diagnostic.severity}:${diagnostic.path?.join(".") ?? ""}:${diagnostic.message}`,
            diagnostic,
        );
    }
    return [...result.values()];
}

function diagnosticsWithRequiredField(
    diagnostics: EditDiagnostic[],
    path: string[],
    value: unknown,
    message = "Required field is missing.",
): EditDiagnostic[] {
    if (value !== undefined && value !== "") {
        return diagnostics;
    }
    if (diagnostics.some(diagnostic => JSON.stringify(diagnostic.path ?? []) === JSON.stringify(path))) {
        return diagnostics;
    }
    return [
        ...diagnostics,
        {
            severity: "required",
            message,
            path,
        },
    ];
}

function resourceWithDiagnostics(
    kind: string,
    name: string,
    parameters: Record<string, unknown>,
    validation: ReturnType<typeof validationForConfig>,
    diagnosticPrefixes: string[][],
    options: ResolvedMigrationResourcesOptions = {},
): ResolvedMigrationResource {
    const diagnostics = diagnosticsForPrefixes(validation.diagnostics, diagnosticPrefixes);
    return {
        ...resource(kind, name, parameters, options),
        ...(diagnostics.length > 0 ? {diagnostics, projectionComplete: false} : {}),
    };
}

function addPathIfDefined(target: Record<string, unknown>, path: string[], value: unknown) {
    if (value !== undefined) {
        setPath(target, path, value);
    }
}

function looseAuthType(kafkaConfig: Record<string, unknown>): string {
    const autoCreate = asRecord(kafkaConfig.autoCreate);
    const auth = asRecord(autoCreate.auth);
    return asString(auth.type) ?? "scram-sha-512";
}

function looseKafkaClusterParameters(kafkaConfig: Record<string, unknown>): Record<string, unknown> {
    const autoCreate = asRecord(kafkaConfig.autoCreate);
    const nodePool = asRecord(autoCreate.nodePoolSpecOverrides);
    const storage = asRecord(nodePool.storage);
    const parameters: Record<string, unknown> = {
        version: KAFKA_VERSION,
    };
    addPathIfDefined(parameters, ["auth", "type"], looseAuthType(kafkaConfig));
    addPathIfDefined(parameters, ["nodePool", "replicas"], nodePool.replicas);
    addPathIfDefined(parameters, ["nodePool", "roles"], nodePool.roles);
    addPathIfDefined(parameters, ["nodePool", "storage", "size"], storage.size);
    addPathIfDefined(parameters, ["nodePool", "storage", "type"], storage.type);
    return parameters;
}

function looseKafkaEntries(config: Record<string, unknown>): [string, Record<string, unknown>][] {
    const explicit = recordEntries(config.kafkaClusterConfiguration);
    if (explicit.length > 0) {
        return explicit;
    }

    const traffic = asRecord(config.traffic);
    const names = new Set<string>();
    for (const [, proxy] of recordEntries(traffic.proxies)) {
        names.add(asString(proxy.kafka) ?? "default");
    }
    for (const [, s3] of recordEntries(traffic.s3Sources)) {
        names.add(asString(s3.kafka) ?? "default");
    }
    return [...names].sort().map(name =>
        [name, {autoCreate: {}} as Record<string, unknown>] as [string, Record<string, unknown>]
    );
}

function looseTopicSpecForKafka(
    kafkaEntries: [string, Record<string, unknown>][],
    kafkaName: string,
): Record<string, unknown> {
    const kafkaConfig = kafkaEntries.find(([name]) => name === kafkaName)?.[1];
    const autoCreate = asRecord(kafkaConfig?.autoCreate);
    return {
        ...DEFAULT_KAFKA_TOPIC_SPEC_OVERRIDES,
        ...asRecord(autoCreate.topicSpecOverrides),
    };
}

function looseCapturedTrafficParameters(
    sourceName: string,
    source: Record<string, unknown>,
    kafkaEntries: [string, Record<string, unknown>][],
): Record<string, unknown> {
    const kafkaName = asString(source.kafka) ?? "default";
    const topicSpec = looseTopicSpecForKafka(kafkaEntries, kafkaName);
    return {
        dependsOn: [kafkaName],
        kafkaClusterName: kafkaName,
        topicName: asString(source.kafkaTopic) ?? sourceName,
        partitions: topicSpec.partitions,
        replicas: topicSpec.replicas,
        topicConfig: topicSpec.config,
    };
}

function looseS3CapturedTrafficParameters(
    sourceName: string,
    source: Record<string, unknown>,
    kafkaEntries: [string, Record<string, unknown>][],
): Record<string, unknown> {
    return {
        ...looseCapturedTrafficParameters(sourceName, source, kafkaEntries),
        sourceKind: "s3",
        s3SourceUri: source.s3Uri,
        loadStarted: true,
    };
}

function looseCaptureProxyParameters(proxyName: string, proxy: Record<string, unknown>): Record<string, unknown> {
    return {
        ...omitFields(asRecord(proxy.proxyConfig), CAPTURE_PROXY_RESOURCE_OMITTED_FIELDS),
        dependsOn: [`${proxyName}-topic`],
    };
}

function looseTrafficReplayParameters(
    replayer: Record<string, unknown>,
): Record<string, unknown> {
    const sourceName = asString(replayer.fromCapturedTraffic);
    return {
        ...asRecord(replayer.replayerConfig),
        dependsOn: sourceName ? [sourceName] : [],
    };
}

function looseSnapshotMigrationParameters(
    migration: Record<string, unknown>,
    snapshotName: string,
    item: Record<string, unknown>,
): Record<string, unknown> {
    const sourceLabel = asString(migration.fromSource);
    const targetLabel = asString(migration.toTarget);
    return {
        ...prefixFields("metadataMigration", asRecord(item.metadataMigrationConfig)),
        ...prefixFields("documentBackfill", asRecord(item.documentBackfillConfig)),
        dependsOn: sourceLabel ? [`${sourceLabel}-${snapshotName}`] : [],
        migrationLabel: asString(item.label) ?? "migration-0",
        sourceLabel,
        targetLabel,
        snapshotLabel: snapshotName,
    };
}

function looseClusterClientConfig(cluster: Record<string, unknown>): Record<string, unknown> {
    const authConfig = asRecord(cluster.authConfig);
    const result: Record<string, unknown> = {
        endpoint: cluster.endpoint,
        version: cluster.version,
        allow_insecure: asBoolean(cluster.allowInsecure),
    };
    if ("basic" in authConfig) {
        const basic = asRecord(authConfig.basic);
        result.basic_auth = {
            k8s_secret_name: basic.secretName,
            user_secret_arn: basic.secretArn,
            username: basic.username,
            password: basic.password,
        };
    } else if ("sigv4" in authConfig) {
        result.sigv4 = authConfig.sigv4 ?? null;
    } else if ("mtls" in authConfig) {
        result.mtls_auth = authConfig.mtls ?? null;
    } else {
        result.no_auth = null;
    }
    return removeUndefined(result) as Record<string, unknown>;
}

function looseKafkaRuntime(kafkaName: string, kafkaConfig: Record<string, unknown>): Record<string, unknown> {
    if ("autoCreate" in kafkaConfig || Object.keys(kafkaConfig).length === 0) {
        const authType = looseAuthType(kafkaConfig);
        return {
            type: "strimzi",
            clusterName: kafkaName,
            authType,
            listenerName: authType === "scram-sha-512" ? "tls" : "plain",
        };
    }

    return {
        type: "direct",
        clientConfig: asRecord(kafkaConfig.existing),
    };
}

function looseConsoleResources(
    rawConfig: Record<string, unknown>,
    workflowName: string | undefined,
    validation: ReturnType<typeof validationForConfig>,
): ConsoleResources {
    const sources = recordEntries(rawConfig.sourceClusters).map(([name, cluster]) => {
        const diagnostics = diagnosticsWithRequiredField(
            diagnosticsForPrefixes(validation.diagnostics, [["sourceClusters", name]]),
            ["sourceClusters", name, "endpoint"],
            cluster.endpoint,
        );
        return {
            refName: name,
            aliases: [name],
            clientConfig: looseClusterClientConfig(cluster),
            source: "config" as const,
            diagnostics,
        };
    });
    const targets = recordEntries(rawConfig.targetClusters).map(([name, cluster]) => {
        const diagnostics = diagnosticsWithRequiredField(
            diagnosticsForPrefixes(validation.diagnostics, [["targetClusters", name]]),
            ["targetClusters", name, "endpoint"],
            cluster.endpoint,
        );
        return {
            refName: name,
            aliases: [name],
            clientConfig: looseClusterClientConfig(cluster),
            source: "config" as const,
            diagnostics,
        };
    });
    const kafkas = looseKafkaEntries(rawConfig).map(([name, kafka]) => ({
        refName: name,
        aliases: [name, `kafkacluster.${name}`],
        ...(("autoCreate" in kafka || Object.keys(kafka).length === 0) ? {k8sName: name} : {}),
        runtime: looseKafkaRuntime(name, kafka) as any,
        source: "config" as const,
        diagnostics: diagnosticsForPrefixes(validation.diagnostics, [["kafkaClusterConfiguration", name]]),
    }));

    return {
        formatVersion: 1,
        ...(workflowName ? {workflowName} : {}),
        sources,
        targets,
        kafkas,
        consumerGroups: [],
    };
}

function buildLooseResourceList(
    rawConfig: Record<string, unknown>,
    validation: ReturnType<typeof validationForConfig>,
    options: ResolvedMigrationResourcesOptions = {},
): ResolvedMigrationResource[] {
    const resources: ResolvedMigrationResource[] = [];
    const kafkaEntries = looseKafkaEntries(rawConfig);

    for (const [name, kafka] of kafkaEntries) {
        if ("autoCreate" in kafka || Object.keys(kafka).length === 0) {
            resources.push(resourceWithDiagnostics(
                "KafkaCluster",
                name,
                looseKafkaClusterParameters(kafka),
                validation,
                [["kafkaClusterConfiguration", name]],
                options,
            ));
        }
    }

    const traffic = asRecord(rawConfig.traffic);
    for (const [proxyName, proxy] of recordEntries(traffic.proxies)) {
        resources.push(resourceWithDiagnostics(
            "CapturedTraffic",
            `${proxyName}-topic`,
            looseCapturedTrafficParameters(proxyName, proxy, kafkaEntries),
            validation,
            [["traffic", "proxies", proxyName, "kafka"], ["traffic", "proxies", proxyName, "kafkaTopic"]],
            options,
        ));
        resources.push(resourceWithDiagnostics(
            "CaptureProxy",
            proxyName,
            looseCaptureProxyParameters(proxyName, proxy),
            validation,
            [["traffic", "proxies", proxyName]],
            options,
        ));
    }

    for (const [s3Name, s3] of recordEntries(traffic.s3Sources)) {
        resources.push(resourceWithDiagnostics(
            "CapturedTraffic",
            `${s3Name}-topic`,
            looseS3CapturedTrafficParameters(s3Name, s3, kafkaEntries),
            validation,
            [["traffic", "s3Sources", s3Name]],
            options,
        ));
    }

    for (const [replayName, replayer] of recordEntries(traffic.replayers)) {
        const fromCapturedTraffic = asString(replayer.fromCapturedTraffic) ?? replayName;
        const toTarget = asString(replayer.toTarget) ?? "target";
        resources.push(resourceWithDiagnostics(
            "TrafficReplay",
            [fromCapturedTraffic, toTarget, replayName].join("-"),
            looseTrafficReplayParameters(replayer),
            validation,
            [["traffic", "replayers", replayName]],
            options,
        ));
    }

    for (const [sourceName, source] of recordEntries(rawConfig.sourceClusters)) {
        const snapshotInfo = asRecord(source.snapshotInfo);
        for (const [snapshotName, snapshot] of recordEntries(snapshotInfo.snapshots)) {
            const config = asRecord(snapshot.config);
            const createSnapshotConfig = asRecord(config.createSnapshotConfig);
            if (Object.keys(createSnapshotConfig).length === 0) {
                continue;
            }
            resources.push(resourceWithDiagnostics(
                "DataSnapshot",
                `${sourceName}-${snapshotName}`,
                {
                    snapshotPrefix: asString(createSnapshotConfig.snapshotPrefix) ?? snapshotName,
                    ...createSnapshotConfig,
                },
                validation,
                [["sourceClusters", sourceName, "snapshotInfo", "snapshots", snapshotName]],
                options,
            ));
        }
    }

    const migrations = Array.isArray(rawConfig.snapshotMigrationConfigs)
        ? rawConfig.snapshotMigrationConfigs
        : [];
    migrations.forEach((migration, migrationIndex) => {
        if (!isRecord(migration)) {
            return;
        }
        const sourceLabel = asString(migration.fromSource) ?? `source-${migrationIndex}`;
        const targetLabel = asString(migration.toTarget) ?? `target-${migrationIndex}`;
        const perSnapshotConfig = asRecord(migration.perSnapshotConfig);
        for (const [snapshotName, itemsValue] of Object.entries(perSnapshotConfig)) {
            const items = Array.isArray(itemsValue) ? itemsValue : [itemsValue];
            items.forEach((item, itemIndex) => {
                if (!isRecord(item)) {
                    return;
                }
                const migrationLabel = asString(item.label) ?? `migration-${itemIndex}`;
                resources.push(resourceWithDiagnostics(
                    "SnapshotMigration",
                    [sourceLabel, targetLabel, snapshotName, migrationLabel].join("-"),
                    looseSnapshotMigrationParameters(migration, snapshotName, item),
                    validation,
                    [["snapshotMigrationConfigs", String(migrationIndex)]],
                    options,
                ));
            });
        }
    });

    return resources;
}

export async function buildLooseResolvedMigrationResources(
    rawConfig: unknown,
    workflowName?: string,
    options: ResolvedMigrationResourcesOptions = {},
): Promise<ResolvedMigrationResources> {
    const validation = validationForConfig(rawConfig);
    try {
        const workflowConfig = await new MigrationConfigTransformer().processFromObject(rawConfig);
        return {
            ...buildResolvedMigrationResources(workflowConfig, workflowName, options),
            projectionMode: "loose",
            projectionComplete: true,
            validation: {
                mode: "loose",
                valid: true,
                errors: [],
            },
        };
    } catch (error) {
        const raw = asRecord(rawConfig);
        const looseValidation = validation.valid
            ? {
                valid: false,
                errors: [String(error)],
                diagnostics: [{
                    severity: "error" as const,
                    message: String(error),
                    path: [],
                }],
            }
            : validation;
        return {
            formatVersion: 1,
            ...(workflowName ? {workflowName} : {}),
            projectionMode: "loose",
            projectionComplete: false,
            validation: {
                mode: "loose",
                valid: looseValidation.valid,
                errors: looseValidation.errors,
                diagnostics: looseValidation.diagnostics,
            },
            consoleResources: looseConsoleResources(raw, workflowName, looseValidation),
            resources: buildLooseResourceList(raw, looseValidation, options),
        };
    }
}

export function dryRunResourcePolicy(
    previous: Pick<ResolvedMigrationResource, "kind" | "name" | "parameters">,
    pending: Pick<ResolvedMigrationResource, "kind" | "name" | "parameters">,
    options: {approved?: boolean} = {}
): VapDryRunResult {
    if (previous.kind !== pending.kind) {
        throw new Error(`Cannot compare ${previous.kind} to ${pending.kind}`);
    }

    const changes: VapDryRunChange[] = [];
    // This mirrors the field-level ValidatingAdmissionPolicy generator in
    // @opensearch-migrations/schemas/generateMigrationResources.ts. Both paths
    // consume collectProjectedFields(), so the same spec paths, changeRestriction
    // values, and invariants drive the real CEL rules and this local preview.
    //
    // The dry run intentionally models only parameter update admission:
    // lifecycle guards such as lock-on-complete and deleting-phase checks depend
    // on live CR status and are evaluated by Kubernetes at apply time. The
    // generated VAP also allows initial spec population while old phase is
    // Created; historical comparisons are made after that initial population.
    for (const field of collectProjectedFields().filter(field => field.resourceKind === pending.kind)) {
            const previousHasValue = hasPath(previous.parameters, field.specPath);
            const pendingHasValue = hasPath(pending.parameters, field.specPath);
            const previousValue = getPath(previous.parameters, field.specPath);
            const pendingValue = getPath(pending.parameters, field.specPath);
            if (previousHasValue === pendingHasValue && stableEqual(previousValue, pendingValue)) {
                continue;
            }

            const path = field.specPath.join(".");
            // Non-decreasing invariants are emitted as their own CEL rule before
            // gated approval is considered, so approval cannot override a decrease.
            if (field.invariant === "nonDecreasing" && previousHasValue && pendingHasValue &&
                typeof previousValue === "number" && typeof pendingValue === "number" &&
                pendingValue < previousValue) {
                changes.push({
                    path,
                    previousValue,
                    pendingValue,
                    changeRestriction: field.changeRestriction,
                    invariant: field.invariant,
                    result: "blocked" as const,
                    message: `${path} cannot decrease.`,
                });
                continue;
            }
            // "impossible" fields are generated as equality-only CEL rules: the
            // pending value must remain absent or equal to the old value.
            if (field.changeRestriction === "impossible") {
                changes.push({
                    path,
                    previousValue,
                    pendingValue,
                    changeRestriction: field.changeRestriction,
                    invariant: field.invariant,
                    result: "blocked" as const,
                    message: `${path} cannot be changed. Delete and recreate.`,
                });
                continue;
            }
            // "gated" fields use the same equality-or-approval shape as the VAP:
            // unchanged values pass, changed values require an ApprovalGate for
            // the workflow run. The caller supplies that approval state here.
            if (field.changeRestriction === "gated" && !options.approved) {
                changes.push({
                    path,
                    previousValue,
                    pendingValue,
                    changeRestriction: field.changeRestriction,
                    invariant: field.invariant,
                    result: "approval-required" as const,
                    message: `${path} requires an ApprovalGate for this workflow run.`,
                });
                continue;
            }
            changes.push({
                path,
                previousValue,
                pendingValue,
                changeRestriction: field.changeRestriction,
                invariant: field.invariant,
                result: "allowed" as const,
                message: `${path} is allowed.`,
            });
    }

    return {
        kind: pending.kind,
        name: pending.name,
        allowed: changes.every(change => change.result === "allowed"),
        changes,
    };
}
