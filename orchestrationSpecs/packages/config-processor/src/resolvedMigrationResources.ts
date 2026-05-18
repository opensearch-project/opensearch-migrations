import {
    ARGO_MIGRATION_CONFIG_PRE_ENRICH,
    collectProjectedFields,
    ProjectedField,
} from "@opensearch-migrations/schemas";
import {z} from "zod";

type WorkflowConfig = z.infer<typeof ARGO_MIGRATION_CONFIG_PRE_ENRICH>;
type KafkaClusterConfig = NonNullable<WorkflowConfig["kafkaClusters"]>[number];
type ProxyConfig = WorkflowConfig["proxies"][number];
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
    parameterPolicies?: ResolvedParameterPolicy[];
}

export interface ResolvedMigrationResources {
    formatVersion: 1;
    workflowName?: string;
    workflowConfig: WorkflowConfig;
    resources: ResolvedMigrationResource[];
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
): ResolvedMigrationResource {
    const normalizedParameters = removeUndefined(parameters) as Record<string, unknown>;
    return {
        apiVersion: CRD_API_VERSION,
        kind,
        name,
        parameters: normalizedParameters,
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

function captureProxyParameters(proxy: ProxyConfig): Record<string, unknown> {
    return {
        ...proxy.proxyConfig,
        dependsOn: [`${proxy.name}-topic`],
    };
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
        resources.push(resource("CaptureProxy", proxy.name, captureProxyParameters(proxy), options));
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
    // on live CR status and are evaluated by Kubernetes at apply time.
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
