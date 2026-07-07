/*
 * Builds and mutates the workflow edit model consumed by `workflow manage`.
 *
 * schemaEditModel.ts renders generic Zod/JSON-schema fields, but the editable
 * workflow contract also needs workflow-specific grouping, add defaults,
 * reference options, omitted/default variant meanings, and YAML replacement
 * rules for auth, Kafka, and proxy TLS. Those rules belong in this TS package
 * because they sit beside the schemas, refinements, and config-to-resource
 * transformer that define the workflow YAML contract.
 *
 * Python should stay a presentation/client layer that invokes this one-shot
 * command and renders the returned tree. If this file grows, prefer moving
 * reusable schema mechanics into schemaEditModel.ts or shared projection code
 * over cloning workflow config policy into Python.
 */
import {
    CLUSTER_CONFIG,
    CAPTURE_CONFIG,
    KAFKA_CLUSTER_CONFIG,
    KAFKA_CLUSTERS_MAP,
    NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG,
    OVERALL_MIGRATION_CONFIG,
    PROXY_TLS_CLIENT_AUTH_CONFIG,
    PROXY_TLS_CONFIG,
    REPLAYER_CONFIG,
    S3_CAPTURED_TRAFFIC_SOURCE,
    SNAPSHOT_INFO,
    SOURCE_CLUSTER_CONFIG,
    SOURCE_CLUSTERS_MAP,
    TARGET_CLUSTER_CONFIG,
    TARGET_CLUSTERS_MAP,
    TRAFFIC_CONFIG,
    USER_PER_INDICES_SNAPSHOT_MIGRATION_CONFIG,
    USER_PROXY_OPTIONS,
    USER_PROXY_PROCESS_OPTION_KEYS,
    USER_PROXY_WORKFLOW_OPTION_KEYS,
} from "@opensearch-migrations/schemas";
import {z} from "zod";
import {stringify} from "yaml";
import {parseYaml} from "./userConfigReader";
import {MigrationConfigTransformer} from "./migrationConfigTransformer";
import {formatInputValidationError, InputValidationError} from "./streamSchemaTransformer";
import {
    EditApplyResultV1,
    EditDiagnostic,
    EditInputHint,
    EditNode,
    EditOperation,
    EditStateV1,
    SchemaEditContext,
    addRow,
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
    singleKeyUnionMode,
    singleKeyUnionValueForVariant,
    uiHintAt,
    uiHintOf,
    unwrapSchema,
    zodEnumValues,
} from "./schemaEditModel";

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
const TRAFFIC_PROXIES_RECORD_HINT = uiHintAt(TRAFFIC_CONFIG, ["proxies"]);
const TRAFFIC_S3_SOURCES_RECORD_HINT = uiHintAt(TRAFFIC_CONFIG, ["s3Sources"]);
const TRAFFIC_REPLAYERS_RECORD_HINT = uiHintAt(TRAFFIC_CONFIG, ["replayers"]);
const SNAPSHOT_PER_CONFIG_HINT = uiHintAt(NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG, ["perSnapshotConfig"]);
const SNAPSHOT_MIGRATION_ARRAY_HINT = uiHintAt(OVERALL_MIGRATION_CONFIG, ["snapshotMigrationConfigs"]) ?? {
    kind: "array" as const,
    addLabel: "snapshot migration",
};
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
    kafkaClusterConfiguration: () => ({autoCreate: {}}),
    "traffic.proxies": () => ({source: "", proxyConfig: {}}),
    "traffic.s3Sources": () => ({s3Uri: "", awsRegion: "", sourceLabel: ""}),
    "traffic.replayers": () => ({fromCapturedTraffic: "", toTarget: ""}),
    snapshotMigrationConfigs: () => ({fromSource: "", toTarget: "", perSnapshotConfig: {}}),
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
            node.removable = true;
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
        inputHint: spec.inputHint,
        status: "ok",
        children,
    });
}

function optionsFromRecord(record: Record<string, unknown> | undefined): EditOption[] {
    if (!record || typeof record !== "object" || Array.isArray(record)) {
        return [];
    }
    return Object.keys(record ?? {})
        .sort((a, b) => a.localeCompare(b))
        .map(name => ({label: name, value: name}));
}

function sourceSnapshotOptions(sourceClusters: Record<string, any> | undefined): Record<string, EditOption[]> {
    return Object.fromEntries(
        Object.entries(sourceClusters ?? {}).map(([sourceName, sourceConfig]) => [
            sourceName,
            optionsFromRecord(sourceConfig?.snapshotInfo?.snapshots),
        ]),
    );
}

function buildEditContext(config: any): EditContext {
    const snapshotOptions = sourceSnapshotOptions(config?.sourceClusters);
    return {
        snapshotSourceOptions: Object.entries(snapshotOptions)
            .filter(([, snapshots]) => snapshots.length > 0)
            .sort(([a], [b]) => a.localeCompare(b))
            .map(([sourceName, snapshots]) => ({
                label: sourceName,
                value: sourceName,
                description: `${snapshots.length} snapshot${snapshots.length === 1 ? "" : "s"} defined`,
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

function snapshotRepoReferenceHint(sourceName: string, repoOptions: EditOption[]): EditInputHint {
    const repoPath = ["sourceClusters", sourceName || "<source>", "snapshotInfo", "repos"];
    return {
        kind: "reference",
        sourcePath: repoPath,
        options: repoOptions,
        message: repoOptions.length
            ? `Choose a repository defined under ${repoPath.join(".")}.`
            : `First define at least one repository under ${repoPath.join(".")} before binding source snapshots.`,
    };
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

function applySnapshotRepoReferenceOptions(children: EditNode[], path: string[], info: Record<string, any>): void {
    const sourceName = path[1] ?? "";
    const repoOptions = optionsFromRecord(info.repos);
    const repoHint = snapshotRepoReferenceHint(sourceName, repoOptions);
    const snapshotsNode = children.find(child => child.path[child.path.length - 1] === "snapshots");
    if (!repoOptions.length && snapshotsNode) {
        const addNode = snapshotsNode.children?.find(child => child.valueKind === "command" && child.id.endsWith(":add"));
        if (addNode) {
            const message = missingSnapshotRepoMessage(sourceName);
            addNode.description = message;
            addNode.command = {...addNode.command, blockedMessage: message};
        }
    }
    for (const snapshotNode of snapshotsNode?.children ?? []) {
        const repoNameNode = snapshotNode.children?.find(child => child.path[child.path.length - 1] === "repoName");
        if (!repoNameNode) {
            continue;
        }
        repoNameNode.inputHint = repoHint;
        repoNameNode.description = withSnapshotRepoHint(repoNameNode.description, sourceName);
    }
}

function snapshotInfoNode(path: string[], snapshotInfo: unknown): EditNode {
    const info = snapshotInfo && typeof snapshotInfo === "object" ? snapshotInfo as any : {};
    const repos = Object.keys(info.repos ?? {}).length;
    const snapshots = Object.keys(info.snapshots ?? {}).length;
    const present = snapshotInfo !== undefined && snapshotInfo !== null;
    const children = schemaFieldNodes(SNAPSHOT_INFO, path, info, [
        "repos",
        "snapshots",
        "serializeSnapshotCreation",
    ]);
    for (const child of children) {
        const childKey = child.path[child.path.length - 1];
        if (childKey === "repos" || childKey === "snapshots") {
            child.essential = true;
        }
        if (!present && childKey === "snapshots") {
            child.presence = "optional";
            child.required = false;
            child.status = "ok";
            child.statusCounts = {};
            child.diagnostics = [];
        }
    }
    applySnapshotRepoReferenceOptions(children, path, info);
    return finalizeNode({
        id: `edit:${path.join(".")}`,
        path,
        label: present
            ? `snapshotInfo: repos ${repos}, snapshots ${snapshots}`
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

function kafkaClusterNode(name: string, value: any): EditNode {
    const rootPath = ["kafkaClusterConfiguration", name];
    const {modeNode, branchChildren} = singleKeyUnionMode(
        rootPath,
        "mode",
        KAFKA_CLUSTER_CONFIG,
        value,
        KAFKA_CLUSTER_DESCRIPTION,
        "autoCreate",
    );
    return finalizeNode({
        id: `edit:${rootPath.join(".")}`,
        path: rootPath,
        label: name,
        valueKind: "object",
        description: KAFKA_CLUSTER_DESCRIPTION,
        status: "ok",
        children: [modeNode, ...branchChildren],
    });
}

function kafkaGroupNode(config: Record<string, any> | undefined): EditNode {
    const path = ["kafkaClusterConfiguration"];
    return recordGroupNode({
        path,
        label: "Kafka Clusters",
        description: KAFKA_CLUSTERS_DESCRIPTION,
        inputHint: KAFKA_RECORD_HINT,
        config,
        itemNode: kafkaClusterNode,
        addLabel: "Kafka cluster",
        addDescription: "Create a Kafka cluster configuration in pending workflow YAML.",
        addInputHint: recordKeyHint(KAFKA_RECORD_HINT),
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
    return finalizeNode({
        id: "edit:traffic",
        path: ["traffic"],
        label: "Live Traffic Migration",
        valueKind: "object",
        description: TRAFFIC_DESCRIPTION,
        status: "ok",
        children: [
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
                path: ["traffic", "s3Sources"],
                label: "Buffer",
                description: schemaFieldDescription(
                    TRAFFIC_CONFIG,
                    "s3Sources",
                    "Optional S3 archives loaded into Kafka for replay when you already have captured traffic and do not need a live capture proxy.",
                ),
                inputHint: TRAFFIC_S3_SOURCES_RECORD_HINT,
                expert: true,
                config: traffic?.s3Sources,
                itemNode: (name, value) => s3CapturedTrafficSourceNode(name, value, ctx),
                addLabel: "optional S3 archive source (no capture proxy)",
                addDescription: "Create an optional pre-recorded traffic source from an S3 archive instead of configuring a live capture proxy.",
                addInputHint: recordKeyHint(TRAFFIC_S3_SOURCES_RECORD_HINT),
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

function snapshotMigrationNode(index: number, value: any, ctx: EditContext): EditNode {
    const rootPath = ["snapshotMigrationConfigs", String(index)];
    const fromSource = value?.fromSource ?? "";
    const toTarget = value?.toTarget ?? "";
    const children = schemaFieldNodes(NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG, rootPath, value, [
        {key: "fromSource", referenceOptions: ctx.snapshotSourceOptions},
        "toTarget",
    ], ctx.schemaContext);
    if (fromSource || Object.keys(value?.perSnapshotConfig ?? {}).length > 0) {
        children.push(snapshotPerConfigNode([...rootPath, "perSnapshotConfig"], value?.perSnapshotConfig, ctx, fromSource));
    }
    children.push(schemaFieldNodeFor(NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG, rootPath, "skipApprovals", value));
    return finalizeNode({
        id: `edit:${rootPath.join(".")}`,
        path: rootPath,
        label: `snapshot migration: ${fromSource || "<source>"} -> ${toTarget || "<target>"}`,
        valueKind: "object",
        removable: true,
        description: SNAPSHOT_MIGRATION_DESCRIPTION,
        status: "ok",
        children,
    });
}

function snapshotPerConfigNode(path: string[], value: unknown, ctx: EditContext, fromSource: string): EditNode {
    const recordValue = isPlainObject(value) ? value : {};
    const snapshotOptions = ctx.sourceSnapshotOptions[fromSource] ?? [];
    const availableNames = snapshotOptions.map(option => option.value);
    const recordNames = Object.keys(recordValue);
    const configuredAvailableCount = recordNames.filter(name => availableNames.includes(name)).length;
    const configuredNames = new Set(recordNames);
    const orderedNames = [
        ...availableNames,
        ...recordNames.filter(name => !availableNames.includes(name)).sort((a, b) => a.localeCompare(b)),
    ];
    const children = orderedNames.map(snapshotName => {
        if (!configuredNames.has(snapshotName)) {
            return snapshotConfigureSlotNode([...path, snapshotName], snapshotName);
        }
        const node = snapshotMigrationPassArrayNode([...path, snapshotName], snapshotName, recordValue[snapshotName]);
        node.removable = true;
        return node;
    });
    const missing = value === undefined || value === null;
    const noSnapshots = Boolean(fromSource) && snapshotOptions.length === 0;
    const diagnostics: EditDiagnostic[] = noSnapshots
        ? [{
            severity: "warning",
            message: `Source '${fromSource}' has no snapshots. Define snapshots under sourceClusters.${fromSource}.snapshotInfo.snapshots before configuring per-snapshot migrations.`,
            path,
        }]
        : [];
    return finalizeNode({
        id: `edit:${path.join(".")}`,
        path,
        label: snapshotPerConfigLabel(missing, configuredAvailableCount, snapshotOptions.length),
        value,
        valueKind: "record",
        presence: "optional",
        essential: true,
        description: schemaFieldDescription(
            NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG,
            "perSnapshotConfig",
            "Per-snapshot migration configurations.",
        ),
        required: false,
        inputHint: SNAPSHOT_PER_CONFIG_HINT,
        status: noSnapshots ? "warning" : "ok",
        diagnostics,
        children,
    });
}

function snapshotPerConfigLabel(missing: boolean, configuredCount: number, availableCount: number): string {
    if (availableCount === 0) {
        return "perSnapshotConfig: no source snapshots";
    }
    if (missing) {
        return `perSnapshotConfig: <unset>, ${availableCount} available`;
    }
    const unconfiguredCount = Math.max(availableCount - configuredCount, 0);
    return `perSnapshotConfig: ${configuredCount} configured, ${unconfiguredCount} unconfigured`;
}

function snapshotConfigureSlotNode(path: string[], snapshotName: string): EditNode {
    return finalizeNode({
        id: `edit:${path.join(".")}:add`,
        path,
        label: `${snapshotName}: not configured`,
        valueKind: "command",
        presence: "optional",
        essential: true,
        description: `Configure migration passes for source snapshot '${snapshotName}'.`,
        command: {requiresName: false, editAdded: false, autoEditAdded: false},
        status: "ok",
    });
}

function snapshotMigrationPassArrayNode(path: string[], snapshotName: string, value: unknown): EditNode {
    const arrayValue = Array.isArray(value) ? value : [];
    const children = [
        ...arrayValue.map((itemValue, index) => snapshotMigrationPassNode([...path, String(index)], index, itemValue)),
        addRow(
            path,
            "migration pass",
            "Add a migration pass for this snapshot. Each pass can migrate metadata, backfill documents, or both.",
            false,
            undefined,
            false,
            false,
            false,
        ),
    ];
    return finalizeNode({
        id: `edit:${path.join(".")}`,
        path,
        label: `${snapshotName}: ${arrayValue.length} item${arrayValue.length === 1 ? "" : "s"}`,
        value,
        valueKind: "array",
        presence: "required",
        essential: true,
        description: "Migration passes to run for this snapshot.",
        required: true,
        status: "ok",
        children,
    });
}

function snapshotMigrationPassNode(path: string[], index: number, value: unknown): EditNode {
    const config = isPlainObject(value) ? value : {};
    const hasMetadata = Object.hasOwn(config, "metadataMigrationConfig");
    const hasBackfill = Object.hasOwn(config, "documentBackfillConfig");
    const children = [
        schemaFieldNodeFor(USER_PER_INDICES_SNAPSHOT_MIGRATION_CONFIG, path, "label", config),
        hasMetadata
            ? essentialSnapshotPassBranch(schemaFieldNodeFor(USER_PER_INDICES_SNAPSHOT_MIGRATION_CONFIG, path, "metadataMigrationConfig", config))
            : addSnapshotMigrationPassBranch(path, "metadataMigrationConfig", "metadata migration"),
        hasBackfill
            ? essentialSnapshotPassBranch(schemaFieldNodeFor(USER_PER_INDICES_SNAPSHOT_MIGRATION_CONFIG, path, "documentBackfillConfig", config))
            : addSnapshotMigrationPassBranch(path, "documentBackfillConfig", "document backfill"),
    ];
    const missingMigrationType = !hasMetadata && !hasBackfill;
    const migrationTypes = [
        hasMetadata ? "metadata" : undefined,
        hasBackfill ? "documents" : undefined,
    ].filter(Boolean).join(" + ");
    return finalizeNode({
        id: `edit:${path.join(".")}`,
        path,
        label: `migration pass ${index + 1}: ${migrationTypes || "choose metadata and/or document backfill"}`,
        value,
        valueKind: "object",
        presence: "required",
        essential: true,
        removable: true,
        description: schemaDescription(USER_PER_INDICES_SNAPSHOT_MIGRATION_CONFIG),
        required: true,
        status: missingMigrationType ? "required" : "ok",
        diagnostics: missingMigrationType
            ? [{
                severity: "required",
                message: "Add metadata migration, document backfill, or both.",
                path,
            }]
            : [],
        children,
    });
}

function addSnapshotMigrationPassBranch(path: string[], key: "metadataMigrationConfig" | "documentBackfillConfig", label: string): EditNode {
    return addRow(
        [...path, key],
        label,
        schemaFieldDescription(USER_PER_INDICES_SNAPSHOT_MIGRATION_CONFIG, key, `Add ${label} configuration.`),
        false,
        undefined,
        false,
        false,
        false,
    );
}

function essentialSnapshotPassBranch(node: EditNode): EditNode {
    node.essential = true;
    return node;
}

function snapshotMigrationGroupNode(configs: any[] | undefined, ctx: EditContext): EditNode {
    const path = ["snapshotMigrationConfigs"];
    const children = (Array.isArray(configs) ? configs : []).map((value, index) => snapshotMigrationNode(index, value, ctx));
    const hasSourceSnapshots = ctx.snapshotSourceOptions.length > 0;
    if (hasSourceSnapshots) {
        children.push(addRow(
            path,
            "snapshot migration",
            "Create a snapshot migration configuration in pending workflow YAML.",
            false,
        ));
    }
    return finalizeNode({
        id: `edit:${path.join(".")}`,
        path,
        label: "Backfill",
        valueKind: "array",
        description: schemaFieldDescription(
            OVERALL_MIGRATION_CONFIG,
            "snapshotMigrationConfigs",
            "List of snapshot-based migration configurations.",
        ),
        inputHint: SNAPSHOT_MIGRATION_ARRAY_HINT,
        status: hasSourceSnapshots || children.length > 0 ? "ok" : "warning",
        diagnostics: hasSourceSnapshots || children.length > 0
            ? []
            : [{
                severity: "warning",
                message: "Define at least one source snapshot under sourceClusters.<source>.snapshotInfo.snapshots before adding a snapshot migration.",
                path,
            }],
        children,
    });
}

function clusterNode(kind: "source" | "target", name: string, value: any): EditNode {
    const rootPath = [kind === "source" ? "sourceClusters" : "targetClusters", name];
    const clusterSchema = kind === "source" ? SOURCE_CLUSTER_CONFIG : TARGET_CLUSTER_CONFIG;
    const children = schemaFieldNodes(clusterSchema, rootPath, value, [
        "endpoint",
        "allowInsecure",
        ...(kind === "source" ? ["version"] : []),
    ]);
    children.push(authNode([...rootPath, "authConfig"], value?.authConfig));
    if (kind === "source") {
        children.push(snapshotInfoNode([...rootPath, "snapshotInfo"], value?.snapshotInfo));
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

function clusterGroupNode(kind: "source" | "target", config: Record<string, any> | undefined): EditNode {
    const path = kind === "source" ? ["sourceClusters"] : ["targetClusters"];
    const recordHint = kind === "source" ? SOURCE_RECORD_HINT : TARGET_RECORD_HINT;
    return recordGroupNode({
        path,
        label: kind === "source" ? "Sources" : "Targets",
        description: kind === "source" ? SOURCE_CLUSTERS_DESCRIPTION : TARGET_CLUSTERS_DESCRIPTION,
        inputHint: recordHint,
        config,
        itemNode: (name, value) => clusterNode(kind, name, value),
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

function workflowConfigurationNode(config: any): EditNode {
    return sectionNode(
        "edit:workflowConfiguration",
        "Workflow Configuration",
        "Shared workflow configuration used by migration resources.",
        [
            kafkaGroupNode(config?.kafkaClusterConfiguration),
            clusterGroupNode("source", config?.sourceClusters),
            clusterGroupNode("target", config?.targetClusters),
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

function validationSuccess(): EditStateV1["validation"] {
    return {valid: true, errors: []};
}

function validationFromError(error: unknown): EditStateV1["validation"] {
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
        return {
            valid: false,
            errors: error.issues.map(issue => `${issue.path.join(".")}: ${issue.message}`),
            diagnostics: error.issues.map(issue => ({
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

export function validationForConfig(config: unknown): EditStateV1["validation"] {
    try {
        new MigrationConfigTransformer().validateInput(config);
        return validationSuccess();
    } catch (error) {
        return validationFromError(error);
    }
}

export async function submitValidationForConfig(config: unknown): Promise<EditStateV1["validation"]> {
    try {
        await new MigrationConfigTransformer().processFromObject(config);
        return validationSuccess();
    } catch (error) {
        return validationFromError(error);
    }
}

export function buildEditStateFromObject(config: any, validationOverride?: EditStateV1["validation"]): EditStateV1 {
    const ctx = buildEditContext(config);
    const nodes = [
        workflowConfigurationNode(config),
        snapshotMigrationSectionNode(config, ctx),
        trafficGroupNode(config?.traffic, ctx),
    ];
    const validation = validationOverride ?? validationForConfig(config);
    applyValidationDiagnostics(nodes, validation.diagnostics ?? []);
    return {
        formatVersion: 1,
        provenance: {
            source: "pending-yaml",
            lossy: false,
            warnings: [],
        },
        nodes,
        validation,
    };
}

export async function buildEditStateFromObjectForSubmit(config: any): Promise<EditStateV1> {
    return buildEditStateFromObject(config, await submitValidationForConfig(config));
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
    if (path[0] === "kafkaClusterConfiguration" && path.length >= 2) {
        return childSchemaAtPath(KAFKA_CLUSTER_CONFIG, path.slice(2));
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

interface ConfigReferenceEdge {
    fromPath: string[];
    fromFieldPath: string[];
    toPath: string[];
    reason: string;
}

function configPathKey(path: string[]): string {
    return path.join("\0");
}

function addConfigReference(
    edges: ConfigReferenceEdge[],
    fromPath: string[],
    fromFieldPath: string[],
    toPath: string[],
    reason: string,
): void {
    edges.push({fromPath, fromFieldPath, toPath, reason});
}

function buildConfigDependencyGraph(config: any): ConfigReferenceEdge[] {
    const edges: ConfigReferenceEdge[] = [];
    const traffic = isPlainObject(config?.traffic) ? config.traffic : {};
    const proxies = isPlainObject(traffic.proxies) ? traffic.proxies : {};
    const s3Sources = isPlainObject(traffic.s3Sources) ? traffic.s3Sources : {};
    const replayers = isPlainObject(traffic.replayers) ? traffic.replayers : {};

    if (Array.isArray(config?.snapshotMigrationConfigs)) {
        config.snapshotMigrationConfigs.forEach((migration: unknown, index: number) => {
            if (!isPlainObject(migration)) {
                return;
            }
            const migrationPath = ["snapshotMigrationConfigs", String(index)];
            const fromSource = typeof migration.fromSource === "string" ? migration.fromSource : "";
            if (fromSource) {
                addConfigReference(
                    edges,
                    migrationPath,
                    [...migrationPath, "fromSource"],
                    ["sourceClusters", fromSource],
                    `fromSource=${fromSource}`,
                );
            }
            const toTarget = typeof migration.toTarget === "string" ? migration.toTarget : "";
            if (toTarget) {
                addConfigReference(
                    edges,
                    migrationPath,
                    [...migrationPath, "toTarget"],
                    ["targetClusters", toTarget],
                    `toTarget=${toTarget}`,
                );
            }
            if (fromSource && isPlainObject(migration.perSnapshotConfig)) {
                for (const snapshotName of Object.keys(migration.perSnapshotConfig)) {
                    const snapshotPath = [...migrationPath, "perSnapshotConfig", snapshotName];
                    addConfigReference(
                        edges,
                        snapshotPath,
                        snapshotPath,
                        ["sourceClusters", fromSource, "snapshotInfo", "snapshots", snapshotName],
                        `snapshot=${snapshotName}`,
                    );
                }
            }
        });
    }

    for (const [proxyName, proxy] of Object.entries(proxies)) {
        if (!isPlainObject(proxy)) {
            continue;
        }
        const proxyPath = ["traffic", "proxies", proxyName];
        if (typeof proxy.source === "string" && proxy.source) {
            addConfigReference(
                edges,
                proxyPath,
                [...proxyPath, "source"],
                ["sourceClusters", proxy.source],
                `source=${proxy.source}`,
            );
        }
        const kafka = typeof proxy.kafka === "string" && proxy.kafka ? proxy.kafka : "default";
        addConfigReference(
            edges,
            proxyPath,
            [...proxyPath, "kafka"],
            ["kafkaClusterConfiguration", kafka],
            `kafka=${kafka}`,
        );
    }

    for (const [s3Name, s3Source] of Object.entries(s3Sources)) {
        if (!isPlainObject(s3Source)) {
            continue;
        }
        const s3Path = ["traffic", "s3Sources", s3Name];
        const kafka = typeof s3Source.kafka === "string" && s3Source.kafka ? s3Source.kafka : "default";
        addConfigReference(
            edges,
            s3Path,
            [...s3Path, "kafka"],
            ["kafkaClusterConfiguration", kafka],
            `kafka=${kafka}`,
        );
    }

    for (const [replayerName, replayer] of Object.entries(replayers)) {
        if (!isPlainObject(replayer)) {
            continue;
        }
        const replayerPath = ["traffic", "replayers", replayerName];
        const fromCapturedTraffic = typeof replayer.fromCapturedTraffic === "string" ? replayer.fromCapturedTraffic : "";
        if (fromCapturedTraffic) {
            const targetRoot = Object.hasOwn(proxies, fromCapturedTraffic)
                ? ["traffic", "proxies", fromCapturedTraffic]
                : Object.hasOwn(s3Sources, fromCapturedTraffic)
                    ? ["traffic", "s3Sources", fromCapturedTraffic]
                    : undefined;
            if (targetRoot) {
                addConfigReference(
                    edges,
                    replayerPath,
                    [...replayerPath, "fromCapturedTraffic"],
                    targetRoot,
                    `fromCapturedTraffic=${fromCapturedTraffic}`,
                );
            }
        }
        const toTarget = typeof replayer.toTarget === "string" ? replayer.toTarget : "";
        if (toTarget) {
            addConfigReference(
                edges,
                replayerPath,
                [...replayerPath, "toTarget"],
                ["targetClusters", toTarget],
                `toTarget=${toTarget}`,
            );
        }
        if (Array.isArray(replayer.dependsOnSnapshotMigrations)) {
            replayer.dependsOnSnapshotMigrations.forEach((dependency: unknown, index: number) => {
                if (!isPlainObject(dependency)) {
                    return;
                }
                const dependencyPath = [...replayerPath, "dependsOnSnapshotMigrations", String(index)];
                const source = typeof dependency.source === "string" ? dependency.source : "";
                if (source) {
                    addConfigReference(
                        edges,
                        dependencyPath,
                        [...dependencyPath, "source"],
                        ["sourceClusters", source],
                        `source=${source}`,
                    );
                }
                const snapshot = typeof dependency.snapshot === "string" ? dependency.snapshot : "";
                if (source && snapshot) {
                    addConfigReference(
                        edges,
                        dependencyPath,
                        [...dependencyPath, "snapshot"],
                        ["sourceClusters", source, "snapshotInfo", "snapshots", snapshot],
                        `snapshot=${snapshot}`,
                    );
                }
            });
        }
    }

    for (const [sourceName, source] of Object.entries(isPlainObject(config?.sourceClusters) ? config.sourceClusters : {})) {
        if (!isPlainObject(source)) {
            continue;
        }
        const snapshotInfo = isPlainObject(source.snapshotInfo) ? source.snapshotInfo : {};
        const repos = snapshotInfo.repos;
        const snapshots = snapshotInfo.snapshots;
        if (!isPlainObject(repos) || !isPlainObject(snapshots)) {
            continue;
        }
        for (const [snapshotName, snapshot] of Object.entries(snapshots)) {
            if (!isPlainObject(snapshot) || typeof snapshot.repoName !== "string" || !snapshot.repoName) {
                continue;
            }
            const snapshotPath = ["sourceClusters", sourceName, "snapshotInfo", "snapshots", snapshotName];
            addConfigReference(
                edges,
                snapshotPath,
                [...snapshotPath, "repoName"],
                ["sourceClusters", sourceName, "snapshotInfo", "repos", snapshot.repoName],
                `repoName=${snapshot.repoName}`,
            );
        }
    }

    return edges;
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
        key === "fromSource" &&
        path.length === 3 &&
        path[0] === "snapshotMigrationConfigs" &&
        isArrayIndex(path[1])
    ) {
        if (parent[key] !== value && isPlainObject(parent.perSnapshotConfig)) {
            delete parent.perSnapshotConfig;
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
    if (key === "mode" && path.length === 3 && path[0] === "kafkaClusterConfiguration") {
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

function removeAtPath(config: any, path: string[]): void {
    if (path.length < 2) {
        throw new Error("Only named config entries can be removed");
    }
    const removedSourceName = sourceClusterRemovedByPath(path);
    const removedSourceSnapshots = sourceSnapshotsRemovedByPath(config, path);
    const {parent, key} = parentAtPath(config, path);
    if (!parent || typeof parent !== "object" || !(key in parent)) {
        throw new Error(`Config entry does not exist at path ${path.join(".")}`);
    }
    if (Array.isArray(parent)) {
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
        config.snapshotMigrationConfigs.push(defaultConfigForPath(path));
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
            return;
        }
        throw new Error("Add operation requires a non-empty name");
    }
    const {parent, key} = parentAtPath(config, [...path, name]);
    if (key in parent) {
        throw new Error(`Config entry already exists at ${[...path, name].join(".")}`);
    }
    parent[key] = defaultConfigForPath(path);
}

export function applyEditOperation(config: any, operation: EditOperation): any {
    const nextConfig = config && typeof config === "object" ? structuredClone(config) : {};
    if (operation.op === "set") {
        setAtPath(nextConfig, operation.path, operation.value);
    } else if (operation.op === "unset") {
        unsetAtPath(nextConfig, operation.path);
    } else if (operation.op === "removeConfig") {
        removeAtPath(nextConfig, operation.path);
    } else if (operation.op === "add") {
        addAtPath(nextConfig, operation.path, operation.value);
    } else {
        const exhaustive: never = operation;
        throw new Error(`Unsupported edit operation: ${JSON.stringify(exhaustive)}`);
    }
    return nextConfig;
}

export function applyEditOperationToObject(config: any, operation: EditOperation): EditApplyResultV1 {
    const nextConfig = applyEditOperation(config, operation);
    const yaml = stringify(nextConfig);
    return {
        formatVersion: 1,
        yaml,
        editState: buildEditStateFromObject(nextConfig),
    };
}

async function applyEditOperationToObjectForSubmit(config: any, operation: EditOperation): Promise<EditApplyResultV1> {
    const nextConfig = applyEditOperation(config, operation);
    const yaml = stringify(nextConfig);
    return {
        formatVersion: 1,
        yaml,
        editState: await buildEditStateFromObjectForSubmit(nextConfig),
    };
}

async function withConsoleDiagnosticsOnStderr<T>(callback: () => T | Promise<T>): Promise<T> {
    const originalLog = console.log;
    const originalInfo = console.info;
    const originalWarn = console.warn;
    const redirect = (...args: unknown[]) => console.error(...args);

    console.log = redirect;
    console.info = redirect;
    console.warn = redirect;
    try {
        return await callback();
    } finally {
        console.log = originalLog;
        console.info = originalInfo;
        console.warn = originalWarn;
    }
}

function usage(): never {
    console.error("Usage: editConfig state --pending-config <file|->");
    console.error("       editConfig apply --pending-config <file|-> --operation <json-file|->");
    process.exit(2);
}

export async function main() {
    const args = process.argv.slice(2);
    const subcommand = args.shift();
    if (subcommand !== "state" && subcommand !== "apply") {
        usage();
    }
    const pendingConfigFlag = args.shift();
    const pendingConfigPath = args.shift();
    if (pendingConfigFlag !== "--pending-config" || !pendingConfigPath) {
        usage();
    }
    const config = await parseYaml(pendingConfigPath);
    if (subcommand === "state") {
        if (args.length > 0) {
            usage();
        }
        const editState = await withConsoleDiagnosticsOnStderr(() => buildEditStateFromObjectForSubmit(config));
        process.stdout.write(JSON.stringify(editState, null, 2));
        return;
    }

    const operationFlag = args.shift();
    const operationPath = args.shift();
    if (operationFlag !== "--operation" || !operationPath || args.length > 0) {
        usage();
    }
    const operation = await parseYaml(operationPath) as EditOperation;
    const result = await withConsoleDiagnosticsOnStderr(() => applyEditOperationToObjectForSubmit(config, operation));
    process.stdout.write(JSON.stringify(result, null, 2));
}

if (require.main === module && !process.env.SUPPRESS_AUTO_LOAD) {
    main().catch(error => {
        console.error(error);
        process.exit(1);
    });
}
