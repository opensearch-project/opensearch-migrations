/*
 * Builds and mutates the browser-safe workflow edit model.
 *
 * schemaEditModel.ts renders generic Zod/JSON-schema fields, but the editable
 * workflow contract also needs workflow-specific grouping, add defaults,
 * reference options, omitted/default variant meanings, and YAML replacement
 * rules for auth, Kafka, and proxy TLS. Those rules belong in this TS package
 * because they sit beside the schemas, refinements, and config-to-resource
 * transformer that define the workflow YAML contract.
 *
 * Node, CLI, Kubernetes, and submit-only transformation behavior belongs in
 * config-processor. This package is shared by that adapter and the browser.
 */
import {
    CLUSTER_CONFIG,
    CAPTURE_CONFIG,
    ELASTICSEARCH_DYNAMIC_SNAPSHOT_CONFIG,
    ELASTICSEARCH_SNAPSHOT_INFO,
    ELASTICSEARCH_SNAPSHOT_NAME_CONFIG,
    KAFKA_CLUSTER_CONFIG,
    KAFKA_CLUSTERS_MAP,
    normalizeLegacySnapshotMigrationSlices,
    NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG,
    OVERALL_MIGRATION_CONFIG,
    PROXY_TLS_CLIENT_AUTH_CONFIG,
    PROXY_TLS_CONFIG,
    REPLAYER_CONFIG,
    S3_CAPTURED_TRAFFIC_SOURCE,
    snapshotRepoRequiresAwsRegion,
    SOLR_SNAPSHOT_INFO,
    SOURCE_CLUSTER_CONFIG,
    SOURCE_CLUSTERS_MAP,
    TARGET_CLUSTER_CONFIG,
    TARGET_CLUSTERS_MAP,
    TRAFFIC_CONFIG,
    USER_SNAPSHOT_MIGRATION_SLICE_CONFIG,
    USER_PROXY_OPTIONS,
    USER_PROXY_PROCESS_OPTION_KEYS,
    USER_PROXY_WORKFLOW_OPTION_KEYS,
} from "@opensearch-migrations/schemas/browser";
import {z} from "zod";
import {parse, stringify} from "yaml";
import {
    buildConfigDependencyGraph,
    downstreamConfigReferences,
    type ConfigReferenceEdge,
} from "./configDependencies";
import {
    formatInputValidationError,
    InputValidationElement,
    InputValidationError,
    stripComments,
} from "./inputValidation";
import {validateNoExtraConfigKeys} from "./extraKeyValidation";
import {validateInputAgainstUnifiedSchema} from "./unifiedSchemaValidator";
import {
    EditApplyResultV1,
    EditDiagnostic,
    EditInputHint,
    EditNode,
    EditOperation,
    EditStateV1,
    JsonSchema,
    SchemaEditContext,
    addRow,
    applyUniqueReferenceDefaults,
    applyValidationDiagnostics,
    childSchemaAtPath,
    defaultJsonValueForSchema,
    defaultValueForSchema,
    discriminatedUnionNode,
    descriptionOf,
    discriminatedUnionOption,
    discriminatedUnionValueForVariant,
    discriminatorForSchema,
    finalizeNode,
    genericDisplayNode,
    isExpertDescription,
    isArrayIndex,
    isPlainObject,
    isRequiredSchema,
    jsonSchemaDiscriminator,
    jsonSchemaEnumValues,
    jsonSchemaForConfigPath,
    jsonSchemaObjectUnionBranches,
    jsonSchemaType,
    jsonDiscriminatedUnionValueForVariant,
    jsonObjectUnionValueForVariant,
    objectChildrenFromValue,
    objectUnionBranches,
    objectUnionValueForVariant,
    optionalObjectToggleNode,
    optionalSingleKeyUnionNode,
    recordKeyHint,
    resolveJsonSchemaRef,
    schemaArrayElement,
    schemaFieldDescription,
    schemaFieldNode,
    schemaFieldNodeFor,
    schemaObjectChildren,
    schemaDescription,
    schemaShape,
    singleKeyUnionBranchFor,
    singleKeyUnionMode,
    singleKeyUnionValueForVariant,
    uiHintAt,
    uiHintOf,
    unwrapSchema,
    zodEnumValues,
    configureEditModelUnifiedSchema,
} from "./schemaEditModel";

export interface ConfigEditCoreOptions {
    unifiedSchema?: JsonSchema;
}

export interface ConfigYamlProjectionV1 {
    config: unknown | null;
    editState: EditStateV1;
}

type EditOption = NonNullable<EditInputHint["options"]>[number];

interface EditContext {
    snapshotSourceOptions: EditOption[];
    sourceSnapshotOptions: Record<string, EditOption[]>;
    schemaContext: SchemaEditContext;
}

const SOURCE_CLUSTER_DESCRIPTION = descriptionOf(SOURCE_CLUSTER_CONFIG);
const TARGET_CLUSTER_DESCRIPTION = descriptionOf(TARGET_CLUSTER_CONFIG);
const SOURCE_CLUSTERS_DESCRIPTION = descriptionOf(SOURCE_CLUSTERS_MAP);
const TARGET_CLUSTERS_DESCRIPTION = descriptionOf(TARGET_CLUSTERS_MAP);
const KAFKA_CLUSTERS_DESCRIPTION = descriptionOf(KAFKA_CLUSTERS_MAP);
const KAFKA_CLUSTER_DESCRIPTION = descriptionOf(KAFKA_CLUSTER_CONFIG);
const TRAFFIC_DESCRIPTION = descriptionOf(TRAFFIC_CONFIG);
const CAPTURE_DESCRIPTION = descriptionOf(CAPTURE_CONFIG);
const REPLAYER_DESCRIPTION = descriptionOf(REPLAYER_CONFIG);
const SNAPSHOT_MIGRATION_DESCRIPTION = descriptionOf(NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG);
const AUTH_CONFIG_SCHEMA = schemaShape(CLUSTER_CONFIG)?.authConfig;
const AUTH_DESCRIPTION = schemaDescription(AUTH_CONFIG_SCHEMA) || "Authentication configuration for connecting to the cluster.";
const S3_CAPTURED_TRAFFIC_DESCRIPTION = descriptionOf(S3_CAPTURED_TRAFFIC_SOURCE);
const KAFKA_RECORD_HINT = uiHintOf(KAFKA_CLUSTERS_MAP);
const SOURCE_RECORD_HINT = uiHintOf(SOURCE_CLUSTERS_MAP);
const TARGET_RECORD_HINT = uiHintOf(TARGET_CLUSTERS_MAP);
const TRAFFIC_KAFKA_RECORD_HINT = uiHintAt(TRAFFIC_CONFIG, ["kafkaClusters"]) ?? KAFKA_RECORD_HINT;
const TRAFFIC_PROXIES_RECORD_HINT = uiHintAt(TRAFFIC_CONFIG, ["proxies"]);
const TRAFFIC_S3_SOURCES_RECORD_HINT = uiHintAt(TRAFFIC_CONFIG, ["s3Sources"]);
const TRAFFIC_REPLAYERS_RECORD_HINT = uiHintAt(TRAFFIC_CONFIG, ["replayers"]);
const SNAPSHOT_MIGRATION_ARRAY_HINT = uiHintAt(OVERALL_MIGRATION_CONFIG, ["snapshotMigrationConfigs"]) ?? {
    kind: "array" as const,
    addLabel: "snapshot migration",
};
const SNAPSHOT_MIGRATION_NAME_HINT = uiHintAt(
    NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG,
    ["slice"],
);
const PROXY_TLS_VARIANT_ORDER = ["existingSecret", "certManager", "plaintext"];
const DEFAULT_CONFIG_FACTORIES: Record<string, () => Record<string, unknown>> = {
    sourceClusters: () => ({
        endpoint: "",
        allowInsecure: false,
        version: "",
    }),
    targetClusters: () => ({
        endpoint: "",
        allowInsecure: false,
    }),
    "traffic.kafkaClusters": () => ({autoCreate: {}, topics: {}}),
    "traffic.proxies": () => ({source: "", kafka: "", kafkaTopic: "", proxyConfig: {}}),
    "traffic.s3Sources": () => ({s3Uri: "", awsRegion: "", kafka: "", kafkaTopic: "", sourceLabel: ""}),
    "traffic.replayers": () => ({fromCapturedTraffic: "", toTarget: ""}),
    snapshotMigrationConfigs: () => ({
        fromSource: "",
        toTarget: "",
        fromSnapshot: "",
        slice: "",
    }),
};

type SchemaFieldSpec = string | { key: string; referenceOptions?: EditInputHint["options"] };

function schemaFieldNodes(
    parentSchema: any,
    rootPath: string[],
    value: unknown,
    fields: SchemaFieldSpec[],
    context?: SchemaEditContext,
): EditNode[] {
    return fields.map(field => typeof field === "string"
        ? schemaFieldNodeFor(parentSchema, rootPath, field, value, undefined, context)
        : schemaFieldNodeFor(parentSchema, rootPath, field.key, value, field.referenceOptions, context));
}

interface RecordGroupSpec {
    path: string[];
    label: string;
    description?: string;
    inputHint?: EditInputHint;
    expert?: boolean;
    essential?: boolean;
    config: Record<string, any> | undefined;
    itemNode: (name: string, value: any) => EditNode;
    addLabel: string;
    addDescription: string;
    addRequiresName?: boolean;
    addInputHint?: EditInputHint;
}

function recordGroupNode(spec: RecordGroupSpec): EditNode {
    const children = Object.entries(spec.config ?? {})
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([name, value]) => {
            const node = spec.itemNode(name, value);
            node.removable = node.implicit !== true;
            return node;
        });
    children.push(addRow(
        spec.path,
        spec.addLabel,
        spec.addDescription,
        spec.addRequiresName ?? true,
        spec.addInputHint,
        spec.expert ?? false,
    ));
    return finalizeNode({
        id: `edit:${spec.path.join(".")}`,
        path: spec.path,
        label: spec.label,
        valueKind: "record",
        description: spec.description,
        expert: spec.expert ?? false,
        essential: spec.essential ?? false,
        inputHint: spec.inputHint,
        status: "ok",
        children,
    });
}

function optionsFromRecord(
    record: Record<string, unknown> | undefined,
    sourcePath: string[],
): EditOption[] {
    if (!record || typeof record !== "object" || Array.isArray(record)) {
        return [];
    }
    return Object.keys(record ?? {})
        .sort((a, b) => a.localeCompare(b))
        .map(name => ({
            label: name,
            value: name,
            editTargetId: `edit:${[...sourcePath, name].join(".")}`,
        }));
}

function sourceSnapshotOptions(sourceClusters: Record<string, any> | undefined): Record<string, EditOption[]> {
    return Object.fromEntries(
        Object.entries(sourceClusters ?? {}).map(([sourceName, sourceConfig]) => {
            const snapshotInfo = sourceConfig?.snapshotInfo;
            const itemKey = isPlainObject(snapshotInfo) && "backups" in snapshotInfo
                ? "backups"
                : "snapshots";
            return [
                sourceName,
                optionsFromRecord(
                    isPlainObject(snapshotInfo)
                        ? snapshotInfo[itemKey] as Record<string, unknown>
                        : undefined,
                    ["sourceClusters", sourceName, "snapshotInfo", itemKey],
                ),
            ];
        }),
    );
}

function buildEditContext(config: any): EditContext {
    const snapshotOptions = sourceSnapshotOptions(config?.sourceClusters);
    return {
        snapshotSourceOptions: Object.entries(snapshotOptions)
            .sort(([a], [b]) => a.localeCompare(b))
            .map(([sourceName, snapshots]) => ({
                label: sourceName,
                value: sourceName,
                description: snapshots.length === 0
                    ? "No snapshots defined"
                    : `${snapshots.length} snapshot${snapshots.length === 1 ? "" : "s"} defined`,
                editTargetId: `edit:sourceClusters.${sourceName}`,
            })),
        sourceSnapshotOptions: snapshotOptions,
        schemaContext: {rootConfig: config},
    };
}

function authNode(path: string[], authConfig: unknown): EditNode {
    return optionalSingleKeyUnionNode(path, "authConfig", AUTH_CONFIG_SCHEMA, authConfig, {
        unsetLabel: "none",
        unsetValue: "none",
        description: AUTH_DESCRIPTION,
        unknownMessage: "Unknown authConfig variant. Expected basic, sigv4, mtls, or omitted.",
    });
}

function proxyTlsMode(config: unknown): "unset" | "certManager" | "existingSecret" | "plaintext" | "unknown" {
    if (!config || typeof config !== "object") {
        return "unset";
    }
    const mode = (config as Record<string, unknown>).mode;
    if (mode === "certManager" || mode === "existingSecret" || mode === "plaintext") {
        return mode;
    }
    return "unknown";
}

function proxyClientAuthNode(path: string[], config: unknown): EditNode {
    const description = descriptionOf(PROXY_TLS_CLIENT_AUTH_CONFIG)
        ?? "Optional mutual TLS client-authentication configuration for the capture proxy listener.";
    return optionalObjectToggleNode(path, "clientAuth", PROXY_TLS_CLIENT_AUTH_CONFIG, config, {
        disabledLabel: "disabled",
        disabledValue: "disabled",
        disabledDescription: "Do not require client certificates when console commands connect to the proxy.",
        enabledLabel: "enabled",
        enabledValue: "enabled",
        enabledDescription: "Require client certificates signed by the configured trusted client CA.",
        description,
        unknownMessage: "Unknown clientAuth value. Expected an object or omission.",
    });
}

function proxyTlsChildren(path: string[], mode: ReturnType<typeof proxyTlsMode>, config: any): EditNode[] {
    if (mode === "unknown") {
        return objectChildrenFromValue(path, config);
    }
    const branch = discriminatedUnionOption(PROXY_TLS_CONFIG, "mode", mode);
    if (!branch) {
        return [];
    }
    const children = schemaObjectChildren(path, branch, config, new Set(["mode", "clientAuth"]));
    if (schemaShape(branch)?.clientAuth) {
        children.push(proxyClientAuthNode([...path, "clientAuth"], config?.clientAuth));
    }
    return children;
}

function proxyTlsNode(
    path: string[],
    schema: any,
    value: unknown,
    hasValue: boolean,
    description: string,
    required: boolean,
    expert: boolean,
    presence: EditNode["presence"],
): EditNode {
    const mode = proxyTlsMode(value);
    const node = discriminatedUnionNode(path, "tls", schema, value, hasValue, description, required, expert, presence);
    if (!node) {
        return genericDisplayNode(path, "tls", value, presence, expert, description);
    }
    node.variants = [
        ...(node.variants ?? []).filter(variant => variant.value === "unset"),
        ...(node.variants ?? [])
            .filter(variant => variant.value !== "unset")
            .sort((left, right) =>
                PROXY_TLS_VARIANT_ORDER.indexOf(String(left.value)) -
                PROXY_TLS_VARIANT_ORDER.indexOf(String(right.value))),
    ];
    node.children = proxyTlsChildren(path, mode, value);
    return finalizeNode(node);
}

function missingSnapshotRepoMessage(sourceName: string): string {
    const repoPath = ["sourceClusters", sourceName || "<source>", "snapshotInfo", "repos"];
    return `First define at least one repository under ${repoPath.join(".")} before adding source snapshots.`;
}

function withSnapshotRepoHint(description: string | undefined, sourceName: string): string {
    const repoPath = `sourceClusters.${sourceName || "<source>"}.snapshotInfo.repos`;
    const hint = `First define repositories under ${repoPath}.`;
    return description?.includes(repoPath)
        ? description
        : `${description ? `${description} ` : ""}${hint}`;
}

function applySnapshotRepoConstraints(children: EditNode[], path: string[], info: Record<string, any>): void {
    const sourceName = path[1] ?? "";
    const hasRepos = isPlainObject(info.repos)
        && Object.keys(info.repos).length > 0;
    const definitionsNode = children.find(child =>
        ["snapshots", "backups"].includes(child.path[child.path.length - 1] ?? ""));
    if (!hasRepos && definitionsNode) {
        const addNode = definitionsNode.children?.find(child => child.valueKind === "command" && child.id.endsWith(":add"));
        if (addNode) {
            const message = missingSnapshotRepoMessage(sourceName);
            addNode.description = message;
            addNode.command = {...addNode.command, blockedMessage: message};
        }
    }
    for (const definitionNode of definitionsNode?.children ?? []) {
        const repoNameNode = definitionNode.children?.find(child => child.path[child.path.length - 1] === "repoName");
        if (!repoNameNode) {
            continue;
        }
        repoNameNode.inputHint = {
            ...repoNameNode.inputHint,
            sourcePath: [
                "sourceClusters",
                sourceName,
                "snapshotInfo",
                "repos",
            ],
            message: hasRepos
                ? `Choose a repository defined under sourceClusters.${sourceName}.snapshotInfo.repos.`
                : `First define at least one repository under sourceClusters.${sourceName}.snapshotInfo.repos before binding source snapshots.`,
        } as EditInputHint;
        repoNameNode.description = withSnapshotRepoHint(repoNameNode.description, sourceName);
    }
}

function applySourceSnapshotReferences(
    children: EditNode[],
    context: SchemaEditContext,
): void {
    const snapshotsNode = children.find(child =>
        child.path[child.path.length - 1] === "snapshots");
    for (const snapshotNode of snapshotsNode?.children ?? []) {
        if (snapshotNode.valueKind === "command") {
            continue;
        }
        const snapshotName = snapshotNode.path.at(-1) ?? "snapshot";
        const snapshotValue = isPlainObject(snapshotNode.value)
            ? snapshotNode.value
            : {};
        const configPath = [...snapshotNode.path, "config"];
        const configNode = optionalSingleKeyUnionNode(
            configPath,
            "config",
            ELASTICSEARCH_SNAPSHOT_NAME_CONFIG,
            snapshotValue.config,
            {
                unsetLabel: "Select snapshot handling",
                unsetValue: "unset",
                description: schemaFieldDescription(
                    ELASTICSEARCH_DYNAMIC_SNAPSHOT_CONFIG,
                    "config",
                    "Choose whether this snapshot is externally managed or created by the workflow.",
                ),
                presence: "required",
            },
            context,
        );
        const configIndex = snapshotNode.children?.findIndex(child =>
            child.path.at(-1) === "config") ?? -1;
        if (configIndex >= 0 && snapshotNode.children) {
            snapshotNode.children[configIndex] = configNode;
        }
        if (configNode.value !== "createSnapshotConfig") {
            continue;
        }
        configNode.referenceTargetId = snapshotNode.id;
        configNode.referenceLabel = `Source Snapshot Definition (${snapshotName})`;
        configNode.description = [
            configNode.description,
            `These settings belong to the source snapshot definition '${snapshotName}'.`,
        ].filter(Boolean).join(" ");
    }
}

function applySnapshotRepoRegionRequirements(children: EditNode[], info: Record<string, any>): void {
    const reposNode = children.find(child =>
        child.path[child.path.length - 1] === "repos");
    for (const repoNode of reposNode?.children ?? []) {
        const repoName = repoNode.path[repoNode.path.length - 1] ?? "";
        if (!snapshotRepoRequiresAwsRegion(info.repos?.[repoName] ?? {})) {
            continue;
        }
        const awsRegionNode = repoNode.children?.find(child =>
            child.path[child.path.length - 1] === "awsRegion");
        if (awsRegionNode) {
            awsRegionNode.presence = "required";
            awsRegionNode.required = true;
        }
    }
}

function sourceVersionIsSolr(version: unknown): boolean {
    return typeof version === "string" && version.startsWith("SOLR ");
}

function snapshotInfoNode(
    path: string[],
    snapshotInfo: unknown,
    sourceVersion: unknown,
    context: SchemaEditContext,
): EditNode {
    const info = snapshotInfo && typeof snapshotInfo === "object" ? snapshotInfo as any : {};
    const isSolrSnapshotInfo = sourceVersionIsSolr(sourceVersion)
        || (isPlainObject(info) && "backups" in info && !("snapshots" in info));
    const schema = isSolrSnapshotInfo ? SOLR_SNAPSHOT_INFO : ELASTICSEARCH_SNAPSHOT_INFO;
    const repos = Object.keys(info.repos ?? {}).length;
    const snapshots = Object.keys(info.snapshots ?? {}).length;
    const backups = Object.keys(info.backups ?? {}).length;
    const present = snapshotInfo !== undefined && snapshotInfo !== null;
    const fields = isSolrSnapshotInfo
        ? ["repos", "backups", "serializeSnapshotCreation"]
        : ["repos", "snapshots", "serializeSnapshotCreation"];
    const children = schemaFieldNodes(schema, path, info, fields, context);
    for (const child of children) {
        const childKey = child.path[child.path.length - 1];
        if (childKey === "repos" || childKey === "snapshots" || childKey === "backups") {
            child.essential = true;
        }
        if (!present && (childKey === "snapshots" || childKey === "backups")) {
            child.presence = "optional";
            child.required = false;
            child.status = "ok";
            child.statusCounts = {};
            child.diagnostics = [];
        }
    }
    applySnapshotRepoRegionRequirements(children, info);
    applySnapshotRepoConstraints(children, path, info);
    applySourceSnapshotReferences(children, context);
    return finalizeNode({
        id: `edit:${path.join(".")}`,
        path,
        label: present
            ? `snapshotInfo: repos ${repos}, ${isSolrSnapshotInfo ? `backups ${backups}` : `snapshots ${snapshots}`}`
            : "snapshotInfo: <unset>",
        value: snapshotInfo,
        valueKind: "object",
        presence: "optional",
        essential: true,
        description: schemaFieldDescription(
            SOURCE_CLUSTER_CONFIG,
            "snapshotInfo",
            "Snapshot repository and snapshot configurations for this source cluster. Required if any snapshot-based migrations reference this source.",
        ),
        status: "ok",
        children,
    });
}

function kafkaClusterNode(name: string, value: any, ctx: EditContext): EditNode {
    const rootPath = ["traffic", "kafkaClusters", name];
    const {modeNode, branchChildren} = singleKeyUnionMode(
        rootPath,
        "mode",
        KAFKA_CLUSTER_CONFIG,
        value,
        KAFKA_CLUSTER_DESCRIPTION,
        "autoCreate",
    );
    const selectedBranch = singleKeyUnionBranchFor(
        KAFKA_CLUSTER_CONFIG,
        String(modeNode.value),
    );
    const topicsNode = selectedBranch
        ? schemaFieldNodeFor(
            selectedBranch.optionSchema,
            rootPath,
            "topics",
            value,
            undefined,
            ctx.schemaContext,
        )
        : undefined;
    return finalizeNode({
        id: `edit:${rootPath.join(".")}`,
        path: rootPath,
        label: name,
        valueKind: "object",
        description: KAFKA_CLUSTER_DESCRIPTION,
        status: "ok",
        children: [modeNode, ...branchChildren, ...(topicsNode ? [topicsNode] : [])],
    });
}

function kafkaGroupNode(
    traffic: Record<string, any> | undefined,
    ctx: EditContext,
): EditNode {
    const path = ["traffic", "kafkaClusters"];
    const authored = isPlainObject(traffic?.kafkaClusters)
        ? traffic.kafkaClusters
        : {};
    return recordGroupNode({
        path,
        label: "Kafka Clusters",
        description: KAFKA_CLUSTERS_DESCRIPTION,
        inputHint: TRAFFIC_KAFKA_RECORD_HINT,
        essential: true,
        config: authored,
        itemNode: (name, value) => kafkaClusterNode(name, value, ctx),
        addLabel: "Kafka cluster",
        addDescription: "Create a Kafka cluster configuration in pending workflow YAML.",
        addInputHint: recordKeyHint(TRAFFIC_KAFKA_RECORD_HINT),
    });
}

function captureProxyConfigFieldNode(
    rootPath: string[],
    key: string,
    schema: any,
    proxyConfig: Record<string, unknown>,
): EditNode {
    if (key !== "tls") {
        return schemaFieldNode(rootPath, key, schema, proxyConfig);
    }
    const description = schemaDescription(schema);
    const required = isRequiredSchema(schema);
    const hasValue = Object.hasOwn(proxyConfig, key);
    return proxyTlsNode(
        [...rootPath, key],
        schema,
        hasValue ? proxyConfig[key] : defaultValueForSchema(schema),
        hasValue,
        description,
        required,
        isExpertDescription(description),
        required ? "required" : "optional",
    );
}

function captureProxyConfigNode(rootPath: string[], value: any): EditNode {
    const proxyConfig = isPlainObject(value) ? value : {};
    const schemaShape = unwrapSchema(USER_PROXY_OPTIONS).shape ?? {};
    const orderedKeys = [
        ...USER_PROXY_WORKFLOW_OPTION_KEYS,
        ...USER_PROXY_PROCESS_OPTION_KEYS,
    ].map(String);
    const knownKeys = new Set(orderedKeys);
    const children = orderedKeys
        .filter(key => schemaShape[key])
        .map(key => captureProxyConfigFieldNode(rootPath, key, schemaShape[key], proxyConfig));
    const extraChildren = Object.keys(proxyConfig)
        .filter(key => !knownKeys.has(key))
        .sort((a, b) => a.localeCompare(b))
        .map(key => genericDisplayNode(
            [...rootPath, key],
            key,
            proxyConfig[key],
            "optional",
            false,
            "Custom capture proxy option not described by the current schema.",
        ));
    return finalizeNode({
        id: `edit:${rootPath.join(".")}`,
        path: rootPath,
        label: "proxyConfig",
        valueKind: "object",
        presence: "required",
        required: true,
        description: schemaFieldDescription(
            CAPTURE_CONFIG,
            "proxyConfig",
            "Process-level and deployment-level configuration options for the capture proxy.",
        ),
        status: "ok",
        children: [...children, ...extraChildren],
    });
}

function captureProxyNode(name: string, value: any, ctx: EditContext): EditNode {
    const rootPath = ["traffic", "proxies", name];
    return finalizeNode({
        id: `edit:${rootPath.join(".")}`,
        path: rootPath,
        label: name,
        valueKind: "object",
        description: CAPTURE_DESCRIPTION,
        status: "ok",
        children: [
            ...schemaFieldNodes(CAPTURE_CONFIG, rootPath, value, [
                "source",
                "kafka",
                "kafkaTopic",
            ], ctx.schemaContext),
            captureProxyConfigNode([...rootPath, "proxyConfig"], value?.proxyConfig),
        ],
    });
}

function s3CapturedTrafficSourceNode(name: string, value: any, ctx: EditContext): EditNode {
    const rootPath = ["traffic", "s3Sources", name];
    return finalizeNode({
        id: `edit:${rootPath.join(".")}`,
        path: rootPath,
        label: name,
        valueKind: "object",
        description: S3_CAPTURED_TRAFFIC_DESCRIPTION,
        status: "ok",
        children: schemaFieldNodes(S3_CAPTURED_TRAFFIC_SOURCE, rootPath, value, [
            "s3Uri",
            "awsRegion",
            "endpoint",
            "kafka",
            "kafkaTopic",
            "sourceLabel",
        ], ctx.schemaContext),
    });
}

function trafficReplayNode(name: string, value: any, ctx: EditContext): EditNode {
    const rootPath = ["traffic", "replayers", name];
    return finalizeNode({
        id: `edit:${rootPath.join(".")}`,
        path: rootPath,
        label: name,
        valueKind: "object",
        description: REPLAYER_DESCRIPTION,
        status: "ok",
        children: schemaFieldNodes(REPLAYER_CONFIG, rootPath, value, [
            "fromCapturedTraffic",
            "toTarget",
            "dependsOnSnapshotMigrations",
            "replayerConfig",
        ], ctx.schemaContext),
    });
}

function trafficGroupNode(traffic: any, ctx: EditContext): EditNode {
    const bufferNode = finalizeNode({
        id: "edit:traffic.buffer",
        path: ["traffic", "buffer"],
        label: "Buffer",
        valueKind: "object",
        description: "Kafka clusters and optional pre-recorded traffic sources used as live-traffic buffers.",
        status: "ok",
        children: [
            kafkaGroupNode(traffic, ctx),
            recordGroupNode({
                path: ["traffic", "s3Sources"],
                label: "Previously Captured Traffic",
                description: schemaFieldDescription(
                    TRAFFIC_CONFIG,
                    "s3Sources",
                    "Previously captured traffic archives loaded from S3 into an explicitly selected Kafka topic for replay.",
                ),
                inputHint: TRAFFIC_S3_SOURCES_RECORD_HINT,
                expert: true,
                config: traffic?.s3Sources,
                itemNode: (name, value) => s3CapturedTrafficSourceNode(name, value, ctx),
                addLabel: "optional S3 archive source (no capture proxy)",
                addDescription: "Create an optional pre-recorded traffic source from an S3 archive instead of configuring a live capture proxy.",
                addInputHint: recordKeyHint(TRAFFIC_S3_SOURCES_RECORD_HINT),
            }),
        ],
    });
    return finalizeNode({
        id: "edit:traffic",
        path: ["traffic"],
        label: "Live Traffic Migration",
        valueKind: "object",
        description: TRAFFIC_DESCRIPTION,
        status: "ok",
        children: [
            bufferNode,
            recordGroupNode({
                path: ["traffic", "proxies"],
                label: "Capture",
                description: schemaFieldDescription(
                    TRAFFIC_CONFIG,
                    "proxies",
                    "Capture proxies that receive source traffic and write it to Kafka.",
                ),
                inputHint: TRAFFIC_PROXIES_RECORD_HINT,
                config: traffic?.proxies,
                itemNode: (name, value) => captureProxyNode(name, value, ctx),
                addLabel: "capture proxy",
                addDescription: "Create a capture proxy configuration in pending workflow YAML.",
                addInputHint: recordKeyHint(TRAFFIC_PROXIES_RECORD_HINT),
            }),
            recordGroupNode({
                path: ["traffic", "replayers"],
                label: "Replay",
                description: schemaFieldDescription(
                    TRAFFIC_CONFIG,
                    "replayers",
                    "Traffic replayers that consume captured traffic and replay it to targets.",
                ),
                inputHint: TRAFFIC_REPLAYERS_RECORD_HINT,
                config: traffic?.replayers,
                itemNode: (name, value) => trafficReplayNode(name, value, ctx),
                addLabel: "traffic replay",
                addDescription: "Create a traffic replay configuration in pending workflow YAML.",
                addInputHint: recordKeyHint(TRAFFIC_REPLAYERS_RECORD_HINT),
            }),
        ],
    });
}

function snapshotMigrationIdentity(value: any): string[] | undefined {
    const identity = [
        value?.fromSource,
        value?.toTarget,
        value?.fromSnapshot,
        value?.slice,
    ];
    return identity.every(part => typeof part === "string" && part.length > 0)
        ? identity
        : undefined;
}

function snapshotMigrationStageBlocker(
    configs: any[],
    index: number,
): string | undefined {
    const identity = snapshotMigrationIdentity(configs[index]);
    if (!identity) {
        return "Choose a source, target, and snapshot before configuring metadata migration or document backfill.";
    }
    const duplicateIndex = configs.findIndex((candidate, candidateIndex) =>
        candidateIndex !== index
        && JSON.stringify(snapshotMigrationIdentity(candidate)) === JSON.stringify(identity));
    if (duplicateIndex >= 0) {
        return `Snapshot migration '${identity.join("-")}' is already configured. Change its name, source, target, or snapshot before configuring migration stages.`;
    }
    return undefined;
}

function snapshotMigrationNode(
    index: number,
    value: any,
    ctx: EditContext,
    stageBlockedMessage?: string,
): EditNode {
    const rootPath = ["snapshotMigrationConfigs", String(index)];
    const fromSource = value?.fromSource ?? "";
    const toTarget = value?.toTarget ?? "";
    const fromSnapshot = value?.fromSnapshot ?? "";
    const slice = value?.slice ?? "";
    const snapshotOptions = ctx.sourceSnapshotOptions[fromSource] ?? [];
    const children = schemaFieldNodes(NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG, rootPath, value, [
        {key: "fromSource", referenceOptions: ctx.snapshotSourceOptions},
        "toTarget",
        {key: "fromSnapshot", referenceOptions: snapshotOptions},
    ], ctx.schemaContext);
    const hasMetadata = Object.hasOwn(value ?? {}, "metadataMigrationConfig");
    const hasBackfill = Object.hasOwn(value ?? {}, "documentBackfillConfig");
    children.push(
        schemaFieldNodeFor(NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG, rootPath, "skipApprovals", value),
        hasMetadata && !stageBlockedMessage
            ? essentialSnapshotSliceBranch(schemaFieldNodeFor(NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG, rootPath, "metadataMigrationConfig", value))
            : addSnapshotMigrationSliceBranch(rootPath, "metadataMigrationConfig", "metadata migration", stageBlockedMessage),
        hasBackfill && !stageBlockedMessage
            ? essentialSnapshotSliceBranch(schemaFieldNodeFor(NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG, rootPath, "documentBackfillConfig", value))
            : addSnapshotMigrationSliceBranch(rootPath, "documentBackfillConfig", "document backfill", stageBlockedMessage),
    );
    const missingMigrationType = !hasMetadata && !hasBackfill;
    return finalizeNode({
        id: `edit:${rootPath.join(".")}`,
        path: rootPath,
        label: [
            fromSource || "<source>",
            toTarget || "<target>",
            fromSnapshot || "<snapshot>",
            slice || "<name>",
        ].join("-"),
        value,
        valueKind: "object",
        removable: true,
        description: SNAPSHOT_MIGRATION_DESCRIPTION,
        status: missingMigrationType ? "required" : "ok",
        diagnostics: missingMigrationType
            ? [{
                severity: "required",
                message: "Add metadata migration, document backfill, or both.",
                path: rootPath,
            }]
            : [],
        children,
    });
}

function addSnapshotMigrationSliceBranch(
    path: string[],
    key: "metadataMigrationConfig" | "documentBackfillConfig",
    label: string,
    blockedMessage?: string,
): EditNode {
    const node = addRow(
        [...path, key],
        label,
        schemaFieldDescription(USER_SNAPSHOT_MIGRATION_SLICE_CONFIG, key, `Add ${label} configuration.`),
        false,
        undefined,
        false,
        false,
        false,
    );
    if (blockedMessage) {
        node.command = {...node.command, blockedMessage};
    }
    return node;
}

function essentialSnapshotSliceBranch(node: EditNode): EditNode {
    node.essential = true;
    node.removable = true;
    return node;
}

function snapshotMigrationGroupNode(configs: any[] | undefined, ctx: EditContext): EditNode {
    const path = ["snapshotMigrationConfigs"];
    const migrationConfigs = Array.isArray(configs) ? configs : [];
    const children = migrationConfigs.map((value, index) => snapshotMigrationNode(
        index,
        value,
        ctx,
        snapshotMigrationStageBlocker(migrationConfigs, index),
    ));
    children.push(addRow(
        path,
        "snapshot migration",
        "Create a snapshot migration configuration in pending workflow YAML.",
        true,
        SNAPSHOT_MIGRATION_NAME_HINT,
    ));
    return finalizeNode({
        id: `edit:${path.join(".")}`,
        path,
        label: "Snapshot migrations",
        valueKind: "array",
        description: schemaFieldDescription(
            OVERALL_MIGRATION_CONFIG,
            "snapshotMigrationConfigs",
            "List of snapshot-based migration configurations.",
        ),
        inputHint: SNAPSHOT_MIGRATION_ARRAY_HINT,
        status: "ok",
        diagnostics: [],
        children,
    });
}

function clusterNode(kind: "source" | "target", name: string, value: any, ctx: EditContext): EditNode {
    const rootPath = [kind === "source" ? "sourceClusters" : "targetClusters", name];
    const clusterSchema = kind === "source" ? SOURCE_CLUSTER_CONFIG : TARGET_CLUSTER_CONFIG;
    const children = schemaFieldNodes(clusterSchema, rootPath, value, [
        "endpoint",
        "allowInsecure",
        ...(kind === "source" ? ["version"] : []),
    ], ctx.schemaContext);
    children.push(authNode([...rootPath, "authConfig"], value?.authConfig));
    if (kind === "source") {
        children.push(snapshotInfoNode(
            [...rootPath, "snapshotInfo"],
            value?.snapshotInfo,
            value?.version,
            ctx.schemaContext,
        ));
    }

    return finalizeNode({
        id: `edit:${rootPath.join(".")}`,
        path: rootPath,
        label: name,
        valueKind: "object",
        description: kind === "source" ? SOURCE_CLUSTER_DESCRIPTION : TARGET_CLUSTER_DESCRIPTION,
        status: "ok",
        children,
    });
}

function clusterGroupNode(
    kind: "source" | "target",
    config: Record<string, any> | undefined,
    ctx: EditContext,
): EditNode {
    const path = kind === "source" ? ["sourceClusters"] : ["targetClusters"];
    const recordHint = kind === "source" ? SOURCE_RECORD_HINT : TARGET_RECORD_HINT;
    return recordGroupNode({
        path,
        label: kind === "source" ? "Sources" : "Targets",
        description: kind === "source" ? SOURCE_CLUSTERS_DESCRIPTION : TARGET_CLUSTERS_DESCRIPTION,
        inputHint: recordHint,
        config,
        itemNode: (name, value) => clusterNode(kind, name, value, ctx),
        addLabel: `${kind} cluster`,
        addDescription: kind === "source"
            ? "Create a new source cluster entry in pending workflow YAML."
            : "Create a new target cluster entry in pending workflow YAML.",
        addInputHint: recordKeyHint(recordHint),
    });
}

function sectionNode(id: string, label: string, description: string, children: EditNode[]): EditNode {
    return finalizeNode({
        id,
        path: [id.replace(/^edit:/, "")],
        label,
        valueKind: "object",
        description,
        status: "ok",
        children,
    });
}

function workflowConfigurationNode(config: any, ctx: EditContext): EditNode {
    return sectionNode(
        "edit:workflowConfiguration",
        "Workflow Configuration",
        "Shared workflow configuration used by migration resources.",
        [
            clusterGroupNode("source", config?.sourceClusters, ctx),
            clusterGroupNode("target", config?.targetClusters, ctx),
        ],
    );
}

function snapshotMigrationSectionNode(config: any, ctx: EditContext): EditNode {
    return sectionNode(
        "edit:snapshotMigration",
        "Snapshot Migration",
        "Snapshot and backfill migration configuration.",
        [
            snapshotMigrationGroupNode(config?.snapshotMigrationConfigs, ctx),
        ],
    );
}

function diagnosticPath(path: PropertyKey[]): string[] {
    return path.map(part => String(part));
}

function messageSeverity(message: string): EditDiagnostic["severity"] {
    const lower = message.toLowerCase();
    if (lower.includes("required") || lower.includes("received undefined")) {
        return "required";
    }
    return "error";
}

function zodIssueSeverity(issue: z.core.$ZodIssue): EditDiagnostic["severity"] {
    if (issue.code === "invalid_type" && (issue as any).input === undefined) {
        return "required";
    }
    return messageSeverity(issue.message);
}

export function validationSuccess(): EditStateV1["validation"] {
    return {valid: true, errors: []};
}

function hasValueAtPath(config: unknown, path: PropertyKey[]): boolean {
    let value = config;
    for (const part of path) {
        if (typeof value !== "object" || value === null || !(part in value)) {
            return false;
        }
        value = (value as any)[part];
    }
    return value !== undefined;
}

function actionableZodIssues(
    issues: z.core.$ZodIssue[],
    config: unknown,
): z.core.$ZodIssue[] {
    return issues.flatMap(issue => {
        if (issue.code === "invalid_key") {
            const nested = (issue as any).issues as z.core.$ZodIssue[] | undefined;
            if (nested?.length) {
                return actionableZodIssues(
                    nested.map(child => ({
                        ...child,
                        path: [...issue.path, ...child.path],
                    })),
                    config,
                );
            }
        }
        if (issue.code !== "invalid_union") {
            return [issue];
        }
        const branches = ((issue as any).errors ?? []) as z.core.$ZodIssue[][];
        const candidates = branches.map(branch => actionableZodIssues(
            branch.map(child => ({
                ...child,
                path: [...issue.path, ...child.path],
            })),
            config,
        ));
        const ranked = candidates
            .map(branch => ({
                branch,
                presentValues: branch.filter(candidate =>
                    hasValueAtPath(config, candidate.path)).length,
            }))
            .sort((left, right) =>
                right.presentValues - left.presentValues
                || left.branch.length - right.branch.length);
        if (ranked[0]?.presentValues) {
            return ranked[0].branch;
        }
        const sharedIssues = (candidates[0] ?? []).filter(candidate =>
            candidates.every(branch => branch.some(other =>
                other.code === candidate.code
                && other.message === candidate.message
                && JSON.stringify(other.path) === JSON.stringify(candidate.path)
            )));
        return sharedIssues.length ? sharedIssues : [issue];
    });
}

export function validationFromError(error: unknown, config?: unknown): EditStateV1["validation"] {
    if (error instanceof InputValidationError) {
        return {
            valid: false,
            errors: [formatInputValidationError(error)],
            diagnostics: error.errors.map(item => ({
                severity: messageSeverity(item.message),
                message: item.message,
                path: diagnosticPath(item.path),
            })),
        };
    }
    if (error instanceof z.ZodError) {
        const issues = config === undefined
            ? error.issues
            : actionableZodIssues(error.issues, config);
        return {
            valid: false,
            errors: issues.map(issue => `${issue.path.join(".")}: ${issue.message}`),
            diagnostics: issues.map(issue => ({
                severity: zodIssueSeverity(issue),
                message: issue.message,
                path: diagnosticPath(issue.path),
            })),
        };
    }
    const message = error instanceof Error ? error.message : String(error);
    return {
        valid: false,
        errors: [message],
        diagnostics: [{severity: "error", message, path: []}],
    };
}

export function inputSchemaValidationError(config: unknown): EditStateV1["validation"] | undefined {
    const strippedConfig = stripComments(config);
    const schemaResult = OVERALL_MIGRATION_CONFIG.safeParse(strippedConfig);
    if (!schemaResult.success) {
        return validationFromError(schemaResult.error, strippedConfig);
    }
    return undefined;
}

export function rawRepairState(
    validation: EditStateV1["validation"],
    warning: string,
): EditStateV1 {
    return {
        formatVersion: 1,
        provenance: {
            source: "pending-yaml",
            lossy: true,
            mode: "raw",
            warnings: [warning],
        },
        nodes: [],
        validation,
    };
}

export function syntaxValidation(error: unknown): EditStateV1["validation"] {
    const message = error instanceof Error ? error.message : String(error);
    return {
        valid: false,
        errors: [message],
        diagnostics: [{
            severity: "error",
            message,
            path: [],
        }],
    };
}

function recordHasObjectValues(value: unknown): boolean {
    return isPlainObject(value)
        && Object.values(value).every(child => isPlainObject(child));
}

function optionalRecordHasObjectValues(value: unknown): boolean {
    return value === undefined || recordHasObjectValues(value);
}

function configShapeSupportsStructuredEdit(config: unknown): boolean {
    if (!isPlainObject(config)) {
        return false;
    }
    if (
        !optionalRecordHasObjectValues(config.sourceClusters)
        || !optionalRecordHasObjectValues(config.targetClusters)
    ) {
        return false;
    }
    if (
        config.snapshotMigrationConfigs !== undefined
        && (
            !Array.isArray(config.snapshotMigrationConfigs)
            || !config.snapshotMigrationConfigs.every(isPlainObject)
        )
    ) {
        return false;
    }
    if (config.traffic !== undefined && !isPlainObject(config.traffic)) {
        return false;
    }
    const traffic = isPlainObject(config.traffic) ? config.traffic : {};
    if (
        !optionalRecordHasObjectValues(traffic.kafkaClusters)
        || !optionalRecordHasObjectValues(traffic.proxies)
        || !optionalRecordHasObjectValues(traffic.s3Sources)
        || !optionalRecordHasObjectValues(traffic.replayers)
    ) {
        return false;
    }
    for (const source of Object.values(
        isPlainObject(config.sourceClusters) ? config.sourceClusters : {}
    )) {
        if (!isPlainObject(source) || source.snapshotInfo === undefined) {
            continue;
        }
        if (!isPlainObject(source.snapshotInfo)) {
            return false;
        }
        if (
            !optionalRecordHasObjectValues(source.snapshotInfo.repos)
            || !optionalRecordHasObjectValues(source.snapshotInfo.snapshots)
            || !optionalRecordHasObjectValues(source.snapshotInfo.backups)
        ) {
            return false;
        }
    }
    return true;
}

function validationRequiresRawRepair(
    config: unknown,
    validation: EditStateV1["validation"],
): boolean {
    if (!configShapeSupportsStructuredEdit(config)) {
        return true;
    }
    return (validation.diagnostics ?? []).some(diagnostic =>
        diagnostic.message.startsWith("Unrecognized key "));
}

export function validationForConfig(
    config: unknown,
    options: ConfigEditCoreOptions = {},
): EditStateV1["validation"] {
    const schemaValidation = inputSchemaValidationError(config);
    if (schemaValidation) {
        return schemaValidation;
    }
    try {
        const parsed = OVERALL_MIGRATION_CONFIG.parse(stripComments(config));
        if (options.unifiedSchema) {
            validateInputAgainstUnifiedSchema(parsed, options.unifiedSchema);
        }
        validateNoExtraConfigKeys(config, OVERALL_MIGRATION_CONFIG);
        return validationSuccess();
    } catch (error) {
        return validationFromError(error);
    }
}

export function buildEditStateFromObject(
    config: any,
    validationOverride?: EditStateV1["validation"],
    options: ConfigEditCoreOptions = {},
): EditStateV1 {
    configureEditModelUnifiedSchema(options.unifiedSchema);
    const normalizedConfig = normalizeLegacySnapshotMigrationSlices(config) as any;
    const ctx = buildEditContext(normalizedConfig);
    const nodes = [
        workflowConfigurationNode(normalizedConfig, ctx),
        snapshotMigrationSectionNode(normalizedConfig, ctx),
        trafficGroupNode(normalizedConfig?.traffic, ctx),
    ];
    const validation = validationOverride ?? validationForConfig(normalizedConfig, options);
    applyValidationDiagnostics(nodes, validation.diagnostics ?? []);
    return {
        formatVersion: 1,
        provenance: {
            source: "pending-yaml",
            lossy: false,
            mode: "structured",
            warnings: [],
        },
        nodes,
        validation,
    };
}

export function buildEditStateFromObjectWithValidation(
    config: any,
    validation: EditStateV1["validation"],
    options: ConfigEditCoreOptions = {},
): EditStateV1 {
    if (validationRequiresRawRepair(config, validation)) {
        return rawRepairState(
            validation,
            "The saved YAML contains structures that cannot be represented safely by the form editor.",
        );
    }
    return buildEditStateFromObject(config, validation, options);
}

function editNodesByPath(editState: EditStateV1): Map<string, EditNode> {
    const result = new Map<string, EditNode>();
    const visit = (nodes: EditNode[]) => {
        for (const node of nodes) {
            if (node.valueKind !== "command") {
                result.set(JSON.stringify(node.path), node);
            }
            visit(node.children ?? []);
        }
    };
    visit(editState.nodes);
    return result;
}

export function annotateDraftChanges(
    editState: EditStateV1,
    baseEditState: EditStateV1,
): EditStateV1 {
    const annotated = structuredClone(editState);
    const baseNodes = editNodesByPath(baseEditState);

    const visit = (node: EditNode): number => {
        const children = node.children ?? [];
        const childCount = children.reduce(
            (count, child) => count + visit(child),
            0,
        );
        if (node.valueKind === "command") {
            return childCount;
        }

        const baseNode = baseNodes.get(JSON.stringify(node.path));
        const comparable = (
            ["scalar", "boolean", "union"].includes(node.valueKind)
            || (children.length === 0 && "value" in node)
        );
        let change: EditNode["draftChange"];
        if (comparable && !baseNode) {
            change = {
                kind: "added",
                previousValuePresent: false,
            };
        } else if (comparable && baseNode) {
            const currentPresent = "value" in node;
            const previousPresent = "value" in baseNode;
            if (
                currentPresent !== previousPresent
                || !Object.is(node.value, baseNode.value)
            ) {
                change = {
                    kind: "modified",
                    previousValuePresent: previousPresent,
                    ...(previousPresent
                        ? {previousValue: structuredClone(baseNode.value)}
                        : {}),
                };
            }
        }

        const currentChildPaths = new Set(
            children
                .filter(child => child.valueKind !== "command")
                .map(child => JSON.stringify(child.path)),
        );
        const removedChildren = (baseNode?.children ?? []).filter(child =>
            child.valueKind !== "command"
            && !currentChildPaths.has(JSON.stringify(child.path)),
        ).length;

        if (change) {
            node.draftChange = change;
        } else {
            delete node.draftChange;
        }
        const changeCount = childCount + removedChildren + (change ? 1 : 0);
        if (changeCount > 0) {
            node.draftChangeCount = changeCount;
        } else {
            delete node.draftChangeCount;
        }
        return changeCount;
    };

    annotated.nodes.forEach(visit);
    return annotated;
}

export function projectConfigYaml(
    rawYaml: string,
    options: ConfigEditCoreOptions = {},
): ConfigYamlProjectionV1 {
    let config: unknown;
    try {
        config = rawYaml.trim() === "" ? {} : parse(rawYaml);
    } catch (error) {
        return {
            config: null,
            editState: rawRepairState(
                syntaxValidation(error),
                "The saved YAML must be repaired before the form editor can open it.",
            ),
        };
    }
    const validation = validationForConfig(config, options);
    return {
        config,
        editState: buildEditStateFromObjectWithValidation(
            config,
            validation,
            options,
        ),
    };
}

function ensureContainer(parent: any, key: string): Record<string, unknown> {
    if (!parent[key] || typeof parent[key] !== "object" || Array.isArray(parent[key])) {
        parent[key] = {};
    }
    return parent[key];
}

function parentAtPath(config: any, path: string[]): { parent: any; key: string } {
    if (path.length === 0) {
        throw new Error("Operation path must not be empty");
    }
    let parent = config;
    const containerPath = path.slice(0, -1);
    for (const [index, part] of containerPath.entries()) {
        const nextPart = containerPath[index + 1] ?? path[path.length - 1];
        if (Array.isArray(parent)) {
            const arrayIndex = Number(part);
            if (!Number.isInteger(arrayIndex) || arrayIndex < 0) {
                throw new Error(`Invalid array index '${part}' in path ${path.join(".")}`);
            }
            if (!parent[arrayIndex] || typeof parent[arrayIndex] !== "object" || Array.isArray(parent[arrayIndex])) {
                parent[arrayIndex] = {};
            }
            parent = parent[arrayIndex];
        } else if (Array.isArray(parent?.[part]) && isArrayIndex(nextPart)) {
            parent = parent[part];
        } else {
            parent = ensureContainer(parent, part);
        }
    }
    return {parent, key: path[path.length - 1]};
}

function existingParentAtPath(config: any, path: string[]): { parent: any; key: string } | undefined {
    if (path.length === 0) {
        throw new Error("Operation path must not be empty");
    }
    let parent = config;
    const containerPath = path.slice(0, -1);
    for (const [index, part] of containerPath.entries()) {
        const nextPart = containerPath[index + 1] ?? path[path.length - 1];
        if (Array.isArray(parent)) {
            const arrayIndex = Number(part);
            if (!Number.isInteger(arrayIndex) || arrayIndex < 0) {
                throw new Error(`Invalid array index '${part}' in path ${path.join(".")}`);
            }
            parent = parent[arrayIndex];
        } else if (Array.isArray(parent?.[part]) && isArrayIndex(nextPart)) {
            parent = parent[part];
        } else {
            parent = parent?.[part];
        }
        if (!parent || typeof parent !== "object") {
            return undefined;
        }
    }
    return {parent, key: path[path.length - 1]};
}

function schemaForConfigPath(path: string[]): any | undefined {
    if (path[0] === "sourceClusters" && path.length >= 2) {
        return childSchemaAtPath(SOURCE_CLUSTER_CONFIG, path.slice(2));
    }
    if (path[0] === "targetClusters" && path.length >= 2) {
        return childSchemaAtPath(TARGET_CLUSTER_CONFIG, path.slice(2));
    }
    if (path[0] === "traffic" && path[1] === "kafkaClusters" && path.length >= 3) {
        return childSchemaAtPath(KAFKA_CLUSTER_CONFIG, path.slice(3));
    }
    if (path[0] === "traffic" && path[1] === "proxies" && path.length >= 3) {
        return childSchemaAtPath(CAPTURE_CONFIG, path.slice(3));
    }
    if (path[0] === "traffic" && path[1] === "s3Sources" && path.length >= 3) {
        return childSchemaAtPath(S3_CAPTURED_TRAFFIC_SOURCE, path.slice(3));
    }
    if (path[0] === "traffic" && path[1] === "replayers" && path.length >= 3) {
        return childSchemaAtPath(REPLAYER_CONFIG, path.slice(3));
    }
    if (path[0] === "snapshotMigrationConfigs" && path.length >= 2 && isArrayIndex(path[1])) {
        return childSchemaAtPath(NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG, path.slice(2));
    }
    return undefined;
}

function authConfigForVariant(existing: any, variant: unknown): unknown {
    if (variant === "none" || variant === null || variant === undefined || variant === "") {
        return undefined;
    }
    if (variant === "basic") {
        return {basic: existing?.basic ?? {}};
    }
    if (variant === "sigv4") {
        return {sigv4: existing?.sigv4 ?? {service: "es"}};
    }
    if (variant === "mtls") {
        return {mtls: existing?.mtls ?? {}};
    }
    throw new Error(`Unknown authConfig variant: ${String(variant)}`);
}

function proxyTlsConfigForVariant(existing: any, variant: unknown): unknown {
    if (variant === "unset" || variant === null || variant === undefined || variant === "") {
        return undefined;
    }
    if (variant === "existingSecret") {
        return {
            mode: "existingSecret",
            secretName: existing?.secretName ?? "",
            ...(existing?.clientAuth ? {clientAuth: existing.clientAuth} : {}),
        };
    }
    if (variant === "certManager") {
        return {
            mode: "certManager",
            issuerRef: existing?.issuerRef ?? {},
            dnsNames: existing?.dnsNames ?? [],
            ...(existing?.commonName ? {commonName: existing.commonName} : {}),
            ...(existing?.duration ? {duration: existing.duration} : {}),
            ...(existing?.renewBefore ? {renewBefore: existing.renewBefore} : {}),
            ...(existing?.clientAuth ? {clientAuth: existing.clientAuth} : {}),
        };
    }
    if (variant === "plaintext") {
        return {mode: "plaintext"};
    }
    throw new Error(`Unknown proxy TLS mode: ${String(variant)}`);
}

function proxyClientAuthForVariant(existing: any, variant: unknown): unknown {
    if (variant === "disabled" || variant === null || variant === undefined || variant === "") {
        return undefined;
    }
    if (variant === "enabled") {
        return isPlainObject(existing) ? existing : {};
    }
    throw new Error(`Unknown proxy clientAuth mode: ${String(variant)}`);
}

function removePerSnapshotConfigReferences(config: any, sourceName: string, snapshotNames: string[]): void {
    const snapshotPaths = new Set(snapshotNames.map(snapshotName =>
        configPathKey(["sourceClusters", sourceName, "snapshotInfo", "snapshots", snapshotName])
    ));
    removeConfigReferencePaths(config, buildConfigDependencyGraph(config)
        .filter(edge => snapshotPaths.has(configPathKey(edge.toPath)))
        .map(edge => edge.fromPath));
}

function sourceClusterRemovedByPath(path: string[]): string | undefined {
    return path[0] === "sourceClusters" && path.length === 2 ? path[1] : undefined;
}

function configPathKey(path: string[]): string {
    return path.join("\0");
}

function startsWithConfigPath(path: string[], prefix: string[]): boolean {
    return path.length >= prefix.length && prefix.every((part, index) => path[index] === part);
}

function removeConfigReferencePaths(config: any, paths: string[][]): void {
    const uniquePaths = [...new Map(paths.map(path => [configPathKey(path), path])).values()];
    uniquePaths.sort((left, right) => {
        const leftParent = left.slice(0, -1);
        const rightParent = right.slice(0, -1);
        if (configPathKey(leftParent) === configPathKey(rightParent) && isArrayIndex(left.at(-1) ?? "") && isArrayIndex(right.at(-1) ?? "")) {
            return Number(right.at(-1)) - Number(left.at(-1));
        }
        return right.length - left.length || configPathKey(right).localeCompare(configPathKey(left));
    });
    for (const path of uniquePaths) {
        const resolved = existingParentAtPath(config, path);
        if (!resolved) {
            continue;
        }
        const {parent, key} = resolved;
        if (Array.isArray(parent) && isArrayIndex(key)) {
            parent.splice(Number(key), 1);
        } else if (parent && typeof parent === "object") {
            delete parent[key];
        }
    }
}

function removeSourceClusterReferences(config: any, sourceName: string): void {
    const graph = buildConfigDependencyGraph(config);
    const sourcePathKey = configPathKey(["sourceClusters", sourceName]);
    const directRemovalPaths = graph
        .filter(edge => configPathKey(edge.toPath) === sourcePathKey)
        .map(edge => edge.fromPath);
    const removedCapturedTrafficKeys = new Set(directRemovalPaths
        .filter(path => path.length === 3 && path[0] === "traffic" && path[1] === "proxies")
        .map(path => configPathKey(path)));
    const replayersForDeletedTraffic = graph
        .filter(edge => removedCapturedTrafficKeys.has(configPathKey(edge.toPath)))
        .map(edge => edge.fromPath);
    removeConfigReferencePaths(config, [...directRemovalPaths, ...replayersForDeletedTraffic]);
}

function renameableConfigPath(path: string[]): boolean {
    if (path.length === 2 && ["sourceClusters", "targetClusters"].includes(path[0])) {
        return true;
    }
    if (path.length === 3 && path[0] === "traffic" && path[1] === "kafkaClusters") {
        return true;
    }
    if (
        path.length === 3
        && path[0] === "traffic"
        && ["proxies", "s3Sources", "replayers"].includes(path[1])
    ) {
        return true;
    }
    if (
        path.length === 5
        && path[0] === "traffic"
        && path[1] === "kafkaClusters"
        && path[3] === "topics"
    ) {
        return true;
    }
    return (
        path.length === 5
        && path[0] === "sourceClusters"
        && path[2] === "snapshotInfo"
        && ["repos", "snapshots"].includes(path[3])
    );
}

function renameConfigKeyAtPath(config: any, path: string[], newName: string): void {
    const resolved = existingParentAtPath(config, path);
    if (!resolved) {
        throw new Error(`Config entry does not exist at path ${path.join(".")}`);
    }
    const {parent, key} = resolved;
    if (!parent || typeof parent !== "object" || Array.isArray(parent)) {
        throw new Error(`Config entry cannot be renamed at path ${path.join(".")}`);
    }
    if (!(key in parent)) {
        throw new Error(`Config entry does not exist at path ${path.join(".")}`);
    }
    if (key === newName) {
        return;
    }
    if (newName in parent) {
        throw new Error(`Config entry already exists at ${[...path.slice(0, -1), newName].join(".")}`);
    }
    parent[newName] = parent[key];
    delete parent[key];
}

function updateConfigReferenceForRename(config: any, edge: ConfigReferenceEdge, newName: string): void {
    if (configPathKey(edge.fromFieldPath) === configPathKey(edge.fromPath)) {
        renameConfigKeyAtPath(config, edge.fromPath, newName);
        return;
    }
    setReferenceValueAtPath(config, edge.fromFieldPath, newName);
}

function setReferenceValueAtPath(config: any, path: string[], value: unknown): void {
    const {parent, key} = parentAtPath(config, path);
    parent[key] = value;
}

function renameAtPath(config: any, path: string[], newNameValue: unknown): void {
    if (!renameableConfigPath(path)) {
        throw new Error(`Rename is not supported at path ${path.join(".")}`);
    }
    const newName = String(newNameValue ?? "").trim();
    if (!newName) {
        throw new Error("Rename requires a non-empty name");
    }
    const oldName = path[path.length - 1];
    if (newName === oldName) {
        return;
    }
    const graph = buildConfigDependencyGraph(config);
    renameConfigKeyAtPath(config, path, newName);
    const oldPathKey = configPathKey(path);
    for (const edge of graph) {
        if (
            configPathKey(edge.toPath) === oldPathKey
            && !startsWithConfigPath(edge.fromPath, path)
        ) {
            updateConfigReferenceForRename(config, edge, newName);
        }
    }
}

function sourceSnapshotsRemovedByPath(config: any, path: string[]): {sourceName: string; snapshotNames: string[]} | undefined {
    if (path[0] !== "sourceClusters" || !path[1]) {
        return undefined;
    }
    const sourceName = path[1];
    const snapshots = config?.sourceClusters?.[sourceName]?.snapshotInfo?.snapshots;
    if (!isPlainObject(snapshots)) {
        return undefined;
    }
    if (path.length === 5 && path[2] === "snapshotInfo" && path[3] === "snapshots") {
        return {sourceName, snapshotNames: [path[4]]};
    }
    if (path.length === 4 && path[2] === "snapshotInfo" && path[3] === "snapshots") {
        return {sourceName, snapshotNames: Object.keys(snapshots)};
    }
    if (path.length === 3 && path[2] === "snapshotInfo") {
        return {sourceName, snapshotNames: Object.keys(snapshots)};
    }
    return undefined;
}

function setAtPath(config: any, path: string[], value: unknown): void {
    const {parent, key} = parentAtPath(config, path);
    if (
        key === "kafka"
        && path.length === 4
        && path[0] === "traffic"
        && ["proxies", "s3Sources"].includes(path[1])
    ) {
        const selectedCluster = config?.traffic?.kafkaClusters?.[String(value)];
        const selectedTopics = isPlainObject(selectedCluster?.topics)
            ? selectedCluster.topics
            : {};
        if (
            typeof parent.kafkaTopic === "string"
            && parent.kafkaTopic
            && !Object.hasOwn(selectedTopics, parent.kafkaTopic)
        ) {
            parent.kafkaTopic = "";
        }
        parent[key] = value;
        return;
    }
    if (
        key === "fromSource" &&
        path.length === 3 &&
        path[0] === "snapshotMigrationConfigs" &&
        isArrayIndex(path[1])
    ) {
        if (parent[key] !== value) {
            const snapshots = sourceSnapshotOptions(config?.sourceClusters)[String(value)] ?? [];
            if (snapshots.length === 1) {
                parent.fromSnapshot = snapshots[0].value;
            } else {
                delete parent.fromSnapshot;
            }
        }
        parent[key] = value;
        return;
    }
    if (key === "authConfig") {
        const next = authConfigForVariant(parent[key], value);
        if (next === undefined) {
            delete parent[key];
        } else {
            parent[key] = next;
        }
        return;
    }
    if (key === "mode" && path.length === 4 && path[0] === "traffic" && path[1] === "kafkaClusters") {
        const replacement = singleKeyUnionValueForVariant(KAFKA_CLUSTER_CONFIG, parent, value);
        for (const existingKey of Object.keys(parent)) {
            delete parent[existingKey];
        }
        Object.assign(parent, replacement);
        return;
    }
    if (key === "tls" && path[path.length - 2] === "proxyConfig") {
        const next = proxyTlsConfigForVariant(parent[key], value);
        if (next === undefined) {
            delete parent[key];
        } else {
            parent[key] = next;
        }
        return;
    }
    if (key === "clientAuth" && path[path.length - 2] === "tls") {
        const next = proxyClientAuthForVariant(parent[key], value);
        if (next === undefined) {
            delete parent[key];
        } else {
            parent[key] = next;
        }
        return;
    }
    const schema = schemaForConfigPath(path);
    if (schema && discriminatorForSchema(schema)) {
        const next = discriminatedUnionValueForVariant(schema, parent[key], value);
        if (next === undefined) {
            delete parent[key];
        } else {
            parent[key] = next;
        }
        return;
    }
    if (schema && objectUnionBranches(schema).length) {
        const next = objectUnionValueForVariant(schema, parent[key], value);
        if (next !== undefined || value === "unset") {
            if (next === undefined) {
                delete parent[key];
            } else {
                parent[key] = next;
            }
            return;
        }
    }
    const jsonSchema = jsonSchemaForConfigPath(path);
    if (jsonSchema && jsonSchemaDiscriminator(jsonSchema)) {
        const next = jsonDiscriminatedUnionValueForVariant(jsonSchema, parent[key], value);
        if (next === undefined) {
            delete parent[key];
        } else {
            parent[key] = next;
        }
        return;
    }
    if (jsonSchema && jsonSchemaObjectUnionBranches(jsonSchema).length) {
        const next = jsonObjectUnionValueForVariant(jsonSchema, parent[key], value);
        if (next === undefined) {
            delete parent[key];
        } else {
            parent[key] = next;
        }
        return;
    }
    if (jsonSchema && jsonSchemaEnumValues(jsonSchema).length > 0 && value === "unset") {
        delete parent[key];
        return;
    }
    if (schema && zodEnumValues(schema).length > 0 && value === "unset") {
        delete parent[key];
        return;
    }
    parent[key] = value;
}

function removeAtPath(
    config: any,
    path: string[],
    referencingResources: "delete" | "clear-references" = "delete",
): void {
    if (path.length < 2) {
        throw new Error("Only named config entries can be removed");
    }
    if (referencingResources === "clear-references") {
        const referenceFields = buildConfigDependencyGraph(config)
            .filter((reference) => (
                startsWithConfigPath(reference.toPath, path)
                && !startsWithConfigPath(reference.fromPath, path)
            ))
            .map((reference) => reference.fromFieldPath);
        removeConfigReferencePaths(config, referenceFields);
    } else {
        const downstreamPaths = downstreamConfigReferences(config, path)
            .map((reference) => reference.fromPath);
        removeConfigReferencePaths(config, downstreamPaths);
    }
    const {parent, key} = parentAtPath(config, path);
    if (!parent || typeof parent !== "object" || !(key in parent)) {
        throw new Error(`Config entry does not exist at path ${path.join(".")}`);
    }
    if (Array.isArray(parent)) {
        parent.splice(Number(key), 1);
        return;
    }
    delete parent[key];
}

function unsetAtPath(config: any, path: string[]): void {
    const removedSourceName = sourceClusterRemovedByPath(path);
    const removedSourceSnapshots = sourceSnapshotsRemovedByPath(config, path);
    const resolved = existingParentAtPath(config, path);
    if (!resolved) {
        return;
    }
    const {parent, key} = resolved;
    if (Array.isArray(parent) && isArrayIndex(key)) {
        parent.splice(Number(key), 1);
        if (removedSourceSnapshots) {
            removePerSnapshotConfigReferences(config, removedSourceSnapshots.sourceName, removedSourceSnapshots.snapshotNames);
        }
        return;
    }
    delete parent[key];
    if (removedSourceName) {
        removeSourceClusterReferences(config, removedSourceName);
    }
    if (removedSourceSnapshots) {
        removePerSnapshotConfigReferences(config, removedSourceSnapshots.sourceName, removedSourceSnapshots.snapshotNames);
    }
}

function defaultConfigForPath(path: string[]): unknown {
    const key = path.join(".");
    const factory = DEFAULT_CONFIG_FACTORIES[key];
    if (factory) {
        return factory();
    }
    const recordValueSchema = zodRecordValueSchema(schemaForConfigPath(path));
    if (recordValueSchema) {
        return defaultConfigValueForSchema(recordValueSchema);
    }
    const recordSchema = resolveJsonSchemaRef(jsonSchemaForConfigPath(path)) as {additionalProperties?: unknown} | undefined;
    const additionalSchema = resolveJsonSchemaRef(
        typeof recordSchema?.additionalProperties === "object" && recordSchema.additionalProperties !== null
            ? recordSchema.additionalProperties as any
            : undefined
    );
    if (additionalSchema) {
        return defaultJsonValueForSchema(additionalSchema);
    }
    throw new Error(`Add is not supported at path ${path.join(".")}`);
}

function zodRecordValueSchema(schema: any): any | undefined {
    const unwrapped = unwrapSchema(schema);
    return String(unwrapped?.constructor?.name ?? "") === "ZodRecord"
        ? unwrapped?.valueType ?? unwrapped?._def?.valueType
        : undefined;
}

function defaultConfigValueForSchema(schema: any): unknown {
    const defaultValue = defaultValueForSchema(schema);
    if (defaultValue !== undefined) {
        return defaultValue;
    }
    if (schemaArrayElement(schema)) {
        return [];
    }
    if (
        schemaShape(schema)
        || zodRecordValueSchema(schema)
        || objectUnionBranches(schema).length > 0
        || discriminatorForSchema(schema)
    ) {
        return {};
    }
    return "";
}

function addAtPath(config: any, path: string[], value: unknown): void {
    if (path.length === 1 && path[0] === "snapshotMigrationConfigs") {
        if (!Array.isArray(config.snapshotMigrationConfigs)) {
            config.snapshotMigrationConfigs = [];
        }
        const requestedName = isPlainObject(value) && typeof value.name === "string"
            ? value.name.trim()
            : "";
        if (!requestedName) {
            throw new InputValidationError([
                new InputValidationElement(path, "A snapshot migration name is required."),
            ]);
        }
        const defaultConfig = defaultConfigForPath(path);
        config.snapshotMigrationConfigs.push({
            ...(isPlainObject(defaultConfig) ? defaultConfig : {}),
            slice: requestedName,
        });
        applyUniqueReferenceDefaults(
            NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG,
            config.snapshotMigrationConfigs.at(-1),
            [...path, String(config.snapshotMigrationConfigs.length - 1)],
            {rootConfig: config},
        );
        const added = config.snapshotMigrationConfigs.at(-1);
        const snapshots = sourceSnapshotOptions(config?.sourceClusters)[String(added?.fromSource ?? "")] ?? [];
        if (snapshots.length === 1) {
            added.fromSnapshot = snapshots[0].value;
        }
        return;
    }

    const arraySchema = resolveJsonSchemaRef(jsonSchemaForConfigPath(path));
    if (jsonSchemaType(arraySchema) === "array") {
        const {parent, key} = parentAtPath(config, path);
        if (!Array.isArray(parent[key])) {
            parent[key] = [];
        }
        const itemSchema = resolveJsonSchemaRef(arraySchema?.items);
        parent[key].push(defaultJsonValueForSchema(itemSchema));
        const addedIndex = parent[key].length - 1;
        applyUniqueReferenceDefaults(
            schemaForConfigPath([...path, String(addedIndex)]),
            parent[key][addedIndex],
            [...path, String(addedIndex)],
            {rootConfig: config},
        );
        return;
    }

    const zodArraySchema = schemaForConfigPath(path);
    const itemSchema = schemaArrayElement(zodArraySchema);
    if (itemSchema) {
        const {parent, key} = parentAtPath(config, path);
        if (!Array.isArray(parent[key])) {
            parent[key] = [];
        }
        parent[key].push(defaultConfigValueForSchema(itemSchema));
        const addedIndex = parent[key].length - 1;
        applyUniqueReferenceDefaults(
            itemSchema,
            parent[key][addedIndex],
            [...path, String(addedIndex)],
            {rootConfig: config},
        );
        return;
    }

    const name = typeof value === "object" && value !== null && "name" in value
        ? String((value as { name: unknown }).name).trim()
        : "";
    if (!name) {
        const schema = schemaForConfigPath(path);
        if (schema) {
            const {parent, key} = parentAtPath(config, path);
            if (key in parent) {
                throw new Error(`Config entry already exists at ${path.join(".")}`);
            }
            parent[key] = defaultConfigValueForSchema(schema);
            applyUniqueReferenceDefaults(schema, parent[key], path, {rootConfig: config});
            return;
        }
        throw new Error("Add operation requires a non-empty name");
    }
    const {parent, key} = parentAtPath(config, [...path, name]);
    if (key in parent) {
        throw new Error(`Config entry already exists at ${[...path, name].join(".")}`);
    }
    parent[key] = defaultConfigForPath(path);
    const addedPath = [...path, name];
    applyUniqueReferenceDefaults(
        schemaForConfigPath(addedPath),
        parent[key],
        addedPath,
        {rootConfig: config},
    );
}

export function applyEditOperation(
    config: any,
    operation: EditOperation,
    options: ConfigEditCoreOptions = {},
): any {
    configureEditModelUnifiedSchema(options.unifiedSchema);
    const normalized = normalizeLegacySnapshotMigrationSlices(config);
    const nextConfig = normalized && typeof normalized === "object"
        ? structuredClone(normalized)
        : {};
    if (operation.op === "set") {
        setAtPath(nextConfig, operation.path, operation.value);
    } else if (operation.op === "unset") {
        unsetAtPath(nextConfig, operation.path);
    } else if (operation.op === "removeConfig") {
        removeAtPath(
            nextConfig,
            operation.path,
            operation.referencingResources,
        );
    } else if (operation.op === "renameConfig") {
        renameAtPath(nextConfig, operation.path, operation.newName);
    } else if (operation.op === "add") {
        addAtPath(nextConfig, operation.path, operation.value);
    } else {
        const exhaustive: never = operation;
        throw new Error(`Unsupported edit operation: ${JSON.stringify(exhaustive)}`);
    }
    return nextConfig;
}

export function applyEditOperationToObject(
    config: any,
    operation: EditOperation,
    options: ConfigEditCoreOptions = {},
): EditApplyResultV1 {
    const nextConfig = applyEditOperation(config, operation, options);
    const yaml = stringify(nextConfig);
    return {
        formatVersion: 1,
        yaml,
        editState: buildEditStateFromObject(nextConfig, undefined, options),
    };
}
