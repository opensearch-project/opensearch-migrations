import {
    CLUSTER_CONFIG,
    CLUSTER_VERSION_STRING,
    CAPTURE_CONFIG,
    FieldMeta,
    HTTP_ENDPOINT_PATTERN,
    HTTP_AUTH_BASIC,
    HTTP_AUTH_MTLS,
    HTTP_AUTH_SIGV4,
    K8S_NAMING_PATTERN,
    KAFKA_CLUSTER_CONFIG,
    KAFKA_CLUSTERS_MAP,
    NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG,
    OPTIONAL_HTTP_ENDPOINT_PATTERN,
    OVERALL_MIGRATION_CONFIG,
    REPLAYER_CONFIG,
    S3_CAPTURED_TRAFFIC_SOURCE,
    SOURCE_CLUSTER_CONFIG,
    SOURCE_CLUSTERS_MAP,
    TARGET_CLUSTER_CONFIG,
    TARGET_CLUSTERS_MAP,
    TRAFFIC_CONFIG,
    UiHint,
    UiTextFormat,
} from "@opensearch-migrations/schemas";
import {z} from "zod";
import {stringify} from "yaml";
import {parseYaml} from "./userConfigReader";
import {MigrationConfigTransformer} from "./migrationConfigTransformer";
import {formatInputValidationError, InputValidationError} from "./streamSchemaTransformer";

export type EditNodeStatus = "ok" | "required" | "error" | "warning" | "changed" | "gated" | "blocked";
export type EditNodeValueKind =
    | "object"
    | "record"
    | "array"
    | "union"
    | "enum"
    | "boolean"
    | "scalar"
    | "command";

export interface EditDiagnostic {
    severity: Exclude<EditNodeStatus, "ok" | "changed">;
    message: string;
    path?: string[];
}

export interface EditNodeValidation {
    pattern?: string;
    message?: string;
}

export type EditInputHint = UiHint & {
    options?: {
        label: string;
        value: string;
        description?: string;
    }[];
};

export interface EditNode {
    id: string;
    path: string[];
    label: string;
    value?: unknown;
    valueKind: EditNodeValueKind;
    description?: string;
    descriptionShort?: string;
    required?: boolean;
    status?: EditNodeStatus;
    statusCounts?: {
        required?: number;
        errors?: number;
        warnings?: number;
        changed?: number;
        gated?: number;
        blocked?: number;
    };
    inputHint?: EditInputHint;
    validation?: EditNodeValidation;
    diagnostics?: EditDiagnostic[];
    variants?: {
        label: string;
        value: unknown;
        description?: string;
        childSchema?: EditNode[];
    }[];
    command?: {
        requiresName?: boolean;
    };
    children?: EditNode[];
}

export interface EditStateV1 {
    formatVersion: 1;
    provenance: {
        source: "pending-yaml";
        lossy: boolean;
        warnings: string[];
    };
    nodes: EditNode[];
    pendingSubmitChanges: unknown[];
    submittedRolloutChanges: unknown[];
    policyPreview: unknown[];
    validation: {
        valid: boolean;
        errors: string[];
        diagnostics?: EditDiagnostic[];
    };
}

export type EditOperation =
    | { op: "set"; path: string[]; value: unknown }
    | { op: "removeConfig"; path: string[] }
    | { op: "add"; path: string[]; value: unknown };

export interface EditApplyResultV1 {
    formatVersion: 1;
    yaml: string;
    editState: EditStateV1;
}

type StatusCounts = NonNullable<EditNode["statusCounts"]>;
type EditOption = NonNullable<EditInputHint["options"]>[number];

interface EditContext {
    sourceOptions: EditOption[];
    targetOptions: EditOption[];
    kafkaOptions: EditOption[];
    proxyOptions: EditOption[];
    capturedTrafficOptions: EditOption[];
}

const STATUS_RANK: Record<EditNodeStatus, number> = {
    blocked: 6,
    error: 5,
    required: 4,
    gated: 3,
    warning: 2,
    changed: 1,
    ok: 0,
};

const CLUSTER_DESCRIPTION = descriptionOf(CLUSTER_CONFIG);
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
const AUTH_DESCRIPTION = "Authentication configuration for connecting to the cluster. Supports HTTP Basic (Kubernetes Secret), AWS SigV4, or mutual TLS.";
const VERSION_INPUT_HINT = uiHintOf(CLUSTER_VERSION_STRING) ?? {
    kind: "text" as const,
    format: "cluster-version" as const,
    pattern: "^(?:ES [125678]|OS [123]|SOLR [6789])(?:\\.[0-9]+)+$",
    message: "Use '<ENGINE> <VERSION>', such as 'ES 7.10.2', 'OS 2.11.0', or 'SOLR 9.7.0'.",
};
const K8S_NAME_INPUT_HINT: EditInputHint = {
    kind: "text",
    format: "k8s-name",
    pattern: K8S_NAMING_PATTERN.source,
    message: "Use a valid Kubernetes DNS name: lowercase letters, numbers, '-' or '.', starting and ending with an alphanumeric character.",
};
const SOURCE_ENDPOINT_HINT = uiHintAt(CLUSTER_CONFIG, ["endpoint"]) ?? textHint(OPTIONAL_HTTP_ENDPOINT_PATTERN, "Leave empty or use an http:// or https:// endpoint with an optional port and trailing slash.", "optional-http-endpoint");
const TARGET_ENDPOINT_HINT = uiHintAt(TARGET_CLUSTER_CONFIG, ["endpoint"]) ?? textHint(HTTP_ENDPOINT_PATTERN, "Use an http:// or https:// endpoint with an optional port and trailing slash.", "http-endpoint");
const BASIC_SECRET_NAME_HINT = uiHintAt(HTTP_AUTH_BASIC, ["basic", "secretName"]) ?? K8S_NAME_INPUT_HINT;
const CAPTURE_SOURCE_HINT = uiHintAt(CAPTURE_CONFIG, ["source"]);
const CAPTURE_KAFKA_HINT = uiHintAt(CAPTURE_CONFIG, ["kafka"]);
const CAPTURE_KAFKA_TOPIC_HINT = uiHintAt(CAPTURE_CONFIG, ["kafkaTopic"]) ?? K8S_NAME_INPUT_HINT;
const REPLAYER_CAPTURED_TRAFFIC_HINT = uiHintAt(REPLAYER_CONFIG, ["fromCapturedTraffic"]);
const REPLAYER_TARGET_HINT = uiHintAt(REPLAYER_CONFIG, ["toTarget"]);
const S3_CAPTURED_TRAFFIC_DESCRIPTION = descriptionOf(S3_CAPTURED_TRAFFIC_SOURCE);
const SNAPSHOT_SOURCE_HINT = uiHintAt(NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG, ["fromSource"]);
const SNAPSHOT_TARGET_HINT = uiHintAt(NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG, ["toTarget"]);
const KAFKA_RECORD_HINT = uiHintOf(KAFKA_CLUSTERS_MAP);
const SOURCE_RECORD_HINT = uiHintOf(SOURCE_CLUSTERS_MAP);
const TARGET_RECORD_HINT = uiHintOf(TARGET_CLUSTERS_MAP);
const TRAFFIC_PROXIES_RECORD_HINT = uiHintAt(TRAFFIC_CONFIG, ["proxies"]);
const TRAFFIC_S3_SOURCES_RECORD_HINT = uiHintAt(TRAFFIC_CONFIG, ["s3Sources"]);
const TRAFFIC_REPLAYERS_RECORD_HINT = uiHintAt(TRAFFIC_CONFIG, ["replayers"]);
const SNAPSHOT_MIGRATION_ARRAY_HINT = uiHintAt(OVERALL_MIGRATION_CONFIG, ["snapshotMigrationConfigs"]) ?? {
    kind: "array" as const,
    addLabel: "snapshot migration",
};

function descriptionOf(schema: {description?: string}): string | undefined {
    return schema.description;
}

function unwrapSchema(schema: any): any {
    if (schema && typeof schema.unwrap === "function") {
        return unwrapSchema(schema.unwrap());
    }
    if (schema && typeof schema.removeDefault === "function") {
        return unwrapSchema(schema.removeDefault());
    }
    return schema;
}

function uiHintOf(schema: any): EditInputHint | undefined {
    const direct = schema?.meta?.() as FieldMeta | undefined;
    const unwrapped = unwrapSchema(schema);
    const inner = unwrapped === schema ? undefined : unwrapped?.meta?.() as FieldMeta | undefined;
    const hint = direct?.uiHint ?? inner?.uiHint;
    return hint ? {...hint} as EditInputHint : undefined;
}

function uiHintAt(schema: any, path: string[]): EditInputHint | undefined {
    let current = schema;
    for (const part of path) {
        const unwrapped = unwrapSchema(current);
        current = unwrapped?.shape?.[part];
        if (!current) {
            return undefined;
        }
    }
    return uiHintOf(current);
}

function textHint(pattern: string, message: string, format?: UiTextFormat): EditInputHint {
    return {
        kind: "text",
        format,
        pattern,
        message,
    };
}

function validationFromHint(inputHint?: EditInputHint): EditNodeValidation | undefined {
    if (!inputHint) {
        return undefined;
    }
    if (inputHint.kind === "text" && inputHint.pattern) {
        return {
            pattern: inputHint.pattern,
            message: inputHint.message,
        };
    }
    return undefined;
}

function optionsFromRecord(record: Record<string, unknown> | undefined): EditOption[] {
    return Object.keys(record ?? {})
        .sort((a, b) => a.localeCompare(b))
        .map(name => ({label: name, value: name}));
}

function capturedTrafficOptions(traffic: any): EditOption[] {
    const names = new Set([
        ...Object.keys(traffic?.proxies ?? {}),
        ...Object.keys(traffic?.s3Sources ?? {}),
    ]);
    return [...names]
        .sort((a, b) => a.localeCompare(b))
        .map(name => ({label: name, value: name}));
}

function buildEditContext(config: any): EditContext {
    return {
        sourceOptions: optionsFromRecord(config?.sourceClusters),
        targetOptions: optionsFromRecord(config?.targetClusters),
        kafkaOptions: optionsFromRecord(config?.kafkaClusterConfiguration),
        proxyOptions: optionsFromRecord(config?.traffic?.proxies),
        capturedTrafficOptions: capturedTrafficOptions(config?.traffic),
    };
}

function referenceHint(baseHint: EditInputHint | undefined, options: EditInputHint["options"]): EditInputHint | undefined {
    if (!baseHint) {
        return undefined;
    }
    return {
        ...baseHint,
        options,
    };
}

function recordKeyHint(recordHint: EditInputHint | undefined): EditInputHint | undefined {
    if (!recordHint || recordHint.kind !== "record") {
        return undefined;
    }
    return {
        kind: "text",
        format: recordHint.keyFormat ?? "text",
        pattern: recordHint.keyPattern,
        message: recordHint.message,
    };
}

function emptyCounts(): StatusCounts {
    return {};
}

function addCount(counts: StatusCounts, status: EditNodeStatus | undefined, amount = 1): void {
    if (!status || status === "ok") {
        return;
    }
    if (status === "required") {
        counts.required = (counts.required ?? 0) + amount;
    } else if (status === "error") {
        counts.errors = (counts.errors ?? 0) + amount;
    } else if (status === "warning") {
        counts.warnings = (counts.warnings ?? 0) + amount;
    } else if (status === "changed") {
        counts.changed = (counts.changed ?? 0) + amount;
    } else if (status === "gated") {
        counts.gated = (counts.gated ?? 0) + amount;
    } else if (status === "blocked") {
        counts.blocked = (counts.blocked ?? 0) + amount;
    }
}

function mergeCounts(target: StatusCounts, source: StatusCounts | undefined): void {
    if (!source) {
        return;
    }
    for (const key of Object.keys(source) as Array<keyof StatusCounts>) {
        target[key] = (target[key] ?? 0) + (source[key] ?? 0);
    }
}

function statusFromCounts(counts: StatusCounts): EditNodeStatus {
    if (counts.blocked) {
        return "blocked";
    }
    if (counts.errors) {
        return "error";
    }
    if (counts.required) {
        return "required";
    }
    if (counts.gated) {
        return "gated";
    }
    if (counts.warnings) {
        return "warning";
    }
    if (counts.changed) {
        return "changed";
    }
    return "ok";
}

function badge(status: EditNodeStatus, counts: StatusCounts = {}): string {
    if (status === "ok") {
        return "[OK]";
    }
    if (status === "required") {
        return `[REQ ${counts.required ?? 1}]`;
    }
    if (status === "error") {
        return `[ERR ${counts.errors ?? 1}]`;
    }
    if (status === "warning") {
        return `[WARN ${counts.warnings ?? 1}]`;
    }
    if (status === "changed") {
        return `[CHG ${counts.changed ?? 1}]`;
    }
    if (status === "gated") {
        return `[GATED ${counts.gated ?? 1}]`;
    }
    return `[BLOCK ${counts.blocked ?? 1}]`;
}

function finalizeNode(node: EditNode): EditNode {
    const counts = emptyCounts();
    addCount(counts, node.status);
    for (const child of node.children ?? []) {
        mergeCounts(counts, child.statusCounts);
        if (!child.statusCounts) {
            addCount(counts, child.status);
        }
    }

    const derivedStatus = statusFromCounts(counts);
    node.status = highestStatus(node.status ?? "ok", derivedStatus);
    node.statusCounts = counts;
    node.label = `${badge(node.status, counts)} ${node.label}`;
    return node;
}

function stripBadge(label: string): string {
    return label.replace(/^\[[^\]]+\]\s*/, "");
}

function refreshNodeBadge(node: EditNode): void {
    node.label = `${badge(node.status ?? "ok", node.statusCounts ?? {})} ${stripBadge(node.label)}`;
}

function samePath(left: unknown[] = [], right: unknown[] = []): boolean {
    return left.length === right.length && left.every((part, index) => String(part) === String(right[index]));
}

function pathStartsWith(path: string[], prefix: string[]): boolean {
    return prefix.length <= path.length && prefix.every((part, index) => part === path[index]);
}

function findPathAncestors(nodes: EditNode[], path: string[]): EditNode[] {
    const ancestors: EditNode[] = [];
    const stack = [...nodes];
    while (stack.length) {
        const node = stack.pop()!;
        if (pathStartsWith(path, node.path)) {
            ancestors.push(node);
            stack.push(...(node.children ?? []));
        }
    }
    ancestors.sort((left, right) => left.path.length - right.path.length);
    return ancestors;
}

function addDiagnosticIfNew(node: EditNode, diagnostic: EditDiagnostic): boolean {
    const existing = node.diagnostics ?? [];
    const isDuplicate = existing.some(item =>
        item.message === diagnostic.message && samePath(item.path, diagnostic.path)
    );
    if (isDuplicate) {
        return false;
    }
    node.diagnostics = [...existing, diagnostic];
    return true;
}

function isMissingRequiredValue(node: EditNode): boolean {
    return node.required === true && (node.value === undefined || node.value === null || node.value === "");
}

function severityForDiagnosticNode(diagnostic: EditDiagnostic, target: EditNode): EditDiagnostic["severity"] {
    if (diagnostic.severity === "error" && (
        isMissingRequiredValue(target) || Boolean(target.statusCounts?.required)
    )) {
        return "required";
    }
    return diagnostic.severity;
}

function applyValidationDiagnostics(nodes: EditNode[], diagnostics: EditDiagnostic[]): void {
    for (const diagnostic of diagnostics) {
        const path = diagnostic.path ?? [];
        const ancestors = findPathAncestors(nodes, path);
        if (!ancestors.length) {
            continue;
        }
        const target = ancestors[ancestors.length - 1];
        const severity = severityForDiagnosticNode(diagnostic, target);
        const adjustedDiagnostic = {...diagnostic, severity};
        if (!addDiagnosticIfNew(target, adjustedDiagnostic)) {
            continue;
        }
        const alreadyRepresentedRequired = (
            diagnostic.severity === "error"
            && severity === "required"
            && Boolean(target.statusCounts?.required)
        );
        for (const node of ancestors) {
            node.statusCounts = node.statusCounts ?? emptyCounts();
            if (!alreadyRepresentedRequired) {
                addCount(node.statusCounts, severity);
            }
            node.status = highestStatus(node.status ?? "ok", severity);
            refreshNodeBadge(node);
        }
    }
}

function highestStatus(a: EditNodeStatus, b: EditNodeStatus): EditNodeStatus {
    return STATUS_RANK[a] >= STATUS_RANK[b] ? a : b;
}

function scalarNode(
    path: string[],
    key: string,
    value: unknown,
    description: string,
    required = false,
    inputHint?: EditInputHint
): EditNode {
    const validation = validationFromHint(inputHint);
    const missing = required && (value === undefined || value === null || value === "");
    const present = value !== undefined && value !== null && value !== "";
    const patternMismatch = !missing && present && validation?.pattern
        ? !(new RegExp(validation.pattern).test(String(value)))
        : false;
    const diagnostics: EditDiagnostic[] = [];
    if (missing) {
        diagnostics.push({severity: "required", message: `${key} is required.`, path});
    } else if (patternMismatch) {
        diagnostics.push({
            severity: "error",
            message: validation?.message ?? `${key} does not match the expected format.`,
            path,
        });
    }
    return finalizeNode({
        id: `edit:${path.join(".")}`,
        path,
        label: `${key}: ${value === undefined || value === null || value === "" ? (required ? "<required>" : "<unset>") : String(value)}`,
        value,
        valueKind: typeof value === "boolean" ? "boolean" : "scalar",
        description,
        required,
        inputHint,
        validation,
        status: missing ? "required" : patternMismatch ? "error" : "ok",
        diagnostics,
    });
}

function booleanNode(path: string[], key: string, value: unknown, description: string): EditNode {
    return finalizeNode({
        id: `edit:${path.join(".")}`,
        path,
        label: `${key}: ${value === true ? "true" : "false"}`,
        value: value === true,
        valueKind: "boolean",
        description,
        status: "ok",
    });
}

function authVariant(authConfig: unknown): "none" | "basic" | "sigv4" | "mtls" | "unknown" {
    if (!authConfig || typeof authConfig !== "object") {
        return "none";
    }
    const keys = Object.keys(authConfig as Record<string, unknown>);
    if (keys.includes("basic")) {
        return "basic";
    }
    if (keys.includes("sigv4")) {
        return "sigv4";
    }
    if (keys.includes("mtls")) {
        return "mtls";
    }
    return "unknown";
}

function authChildren(path: string[], variant: ReturnType<typeof authVariant>, authConfig: any): EditNode[] {
    if (variant === "basic") {
        return [
            scalarNode(
                [...path, "basic", "secretName"],
                "secretName",
                authConfig?.basic?.secretName,
                "Name of a Kubernetes Secret containing 'username' and 'password' keys for HTTP Basic authentication.",
                true,
                BASIC_SECRET_NAME_HINT
            ),
        ];
    }
    if (variant === "sigv4") {
        return [
            scalarNode([...path, "sigv4", "region"], "region", authConfig?.sigv4?.region, "AWS region for SigV4 request signing (e.g. 'us-east-1').", true),
            scalarNode([...path, "sigv4", "service"], "service", authConfig?.sigv4?.service ?? "es", "AWS service name for SigV4 signing. Use 'es' for Amazon OpenSearch Service or 'aoss' for OpenSearch Serverless."),
        ];
    }
    if (variant === "mtls") {
        return [
            scalarNode([...path, "mtls", "caCert"], "caCert", authConfig?.mtls?.caCert, "PEM-encoded CA certificate or path to CA certificate file for verifying the server's TLS certificate.", true),
            scalarNode([...path, "mtls", "clientSecretName"], "clientSecretName", authConfig?.mtls?.clientSecretName, "Name of a Kubernetes TLS Secret containing the client certificate and private key for mutual TLS authentication.", true, K8S_NAME_INPUT_HINT),
        ];
    }
    return [];
}

function authNode(path: string[], authConfig: unknown): EditNode {
    const variant = authVariant(authConfig);
    const diagnostics: EditDiagnostic[] = variant === "unknown"
        ? [{severity: "error", message: "Unknown authConfig variant. Expected basic, sigv4, mtls, or omitted.", path}]
        : [];
    return finalizeNode({
        id: `edit:${path.join(".")}`,
        path,
        label: `authConfig: < ${variant} >`,
        value: variant,
        valueKind: "union",
        description: AUTH_DESCRIPTION,
        descriptionShort: AUTH_DESCRIPTION,
        status: variant === "unknown" ? "error" : "ok",
        diagnostics,
        variants: [
            {label: "none", value: "none"},
            {label: "basic", value: "basic", description: descriptionOf(HTTP_AUTH_BASIC)},
            {label: "sigv4", value: "sigv4", description: descriptionOf(HTTP_AUTH_SIGV4)},
            {label: "mtls", value: "mtls", description: descriptionOf(HTTP_AUTH_MTLS)},
        ],
        children: authChildren(path, variant, authConfig),
    });
}

function snapshotInfoNode(path: string[], snapshotInfo: unknown): EditNode {
    const info = snapshotInfo && typeof snapshotInfo === "object" ? snapshotInfo as any : {};
    const repos = Object.keys(info.repos ?? {}).length;
    const snapshots = Object.keys(info.snapshots ?? {}).length;
    return finalizeNode({
        id: `edit:${path.join(".")}`,
        path,
        label: `snapshotInfo: repos ${repos}, snapshots ${snapshots}`,
        value: snapshotInfo,
        valueKind: "object",
        description: "Snapshot repository and snapshot configurations for this source cluster. Required if any snapshot-based migrations reference this source.",
        status: "ok",
    });
}

function kafkaMode(config: unknown): "autoCreate" | "existing" | "unknown" {
    if (!config || typeof config !== "object") {
        return "autoCreate";
    }
    const keys = Object.keys(config as Record<string, unknown>);
    if (keys.includes("autoCreate")) {
        return "autoCreate";
    }
    if (keys.includes("existing")) {
        return "existing";
    }
    return "unknown";
}

function kafkaClusterNode(name: string, value: any): EditNode {
    const rootPath = ["kafkaClusterConfiguration", name];
    const mode = kafkaMode(value);
    const children: EditNode[] = [
        finalizeNode({
            id: `edit:${[...rootPath, "mode"].join(".")}`,
            path: [...rootPath, "mode"],
            label: `mode: < ${mode} >`,
            value: mode,
            valueKind: "union",
            description: KAFKA_CLUSTER_DESCRIPTION,
            status: mode === "unknown" ? "error" : "ok",
            diagnostics: mode === "unknown"
                ? [{severity: "error", message: "Unknown Kafka cluster variant. Expected autoCreate or existing.", path: rootPath}]
                : [],
            variants: [
                {label: "autoCreate", value: "autoCreate"},
                {label: "existing", value: "existing"},
            ],
        }),
    ];
    if (mode === "existing") {
        children.push(
            scalarNode(
                [...rootPath, "existing", "bootstrapServers"],
                "bootstrapServers",
                value?.existing?.bootstrapServers,
                "Kafka bootstrap servers for an existing cluster.",
                true
            )
        );
    }
    return finalizeNode({
        id: `edit:${rootPath.join(".")}`,
        path: rootPath,
        label: `kafka: ${name}`,
        valueKind: "object",
        description: KAFKA_CLUSTER_DESCRIPTION,
        status: "ok",
        children,
    });
}

function kafkaGroupNode(config: Record<string, any> | undefined): EditNode {
    const path = ["kafkaClusterConfiguration"];
    const children = Object.entries(config ?? {})
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([name, value]) => kafkaClusterNode(name, value));
    children.push(addRow(path, "Kafka cluster", "Create a Kafka cluster configuration in pending workflow YAML.", true, recordKeyHint(KAFKA_RECORD_HINT)));
    return finalizeNode({
        id: `edit:${path.join(".")}`,
        path,
        label: "Kafka Clusters",
        valueKind: "record",
        description: KAFKA_CLUSTERS_DESCRIPTION,
        inputHint: KAFKA_RECORD_HINT,
        status: "ok",
        children,
    });
}

function captureProxyNode(name: string, value: any, ctx: EditContext): EditNode {
    const rootPath = ["traffic", "proxies", name];
    return finalizeNode({
        id: `edit:${rootPath.join(".")}`,
        path: rootPath,
        label: `capture proxy: ${name}`,
        valueKind: "object",
        description: CAPTURE_DESCRIPTION,
        status: "ok",
        children: [
            scalarNode([...rootPath, "source"], "source", value?.source, "Name of the source cluster this proxy sits in front of. Must match a key in sourceClusters.", true, referenceHint(CAPTURE_SOURCE_HINT, ctx.sourceOptions)),
            scalarNode([...rootPath, "kafka"], "kafka", value?.kafka ?? "default", "Label of the Kafka cluster to use for captured traffic. Must match a key in kafkaClusterConfiguration.", false, referenceHint(CAPTURE_KAFKA_HINT, ctx.kafkaOptions)),
            scalarNode([...rootPath, "kafkaTopic"], "kafkaTopic", value?.kafkaTopic ?? "", "Kafka topic name for captured traffic. If empty, defaults to the proxy name.", false, CAPTURE_KAFKA_TOPIC_HINT),
        ],
    });
}

function s3CapturedTrafficSourceNode(name: string, value: any, ctx: EditContext): EditNode {
    const rootPath = ["traffic", "s3Sources", name];
    return finalizeNode({
        id: `edit:${rootPath.join(".")}`,
        path: rootPath,
        label: `S3 captured traffic source: ${name}`,
        valueKind: "object",
        description: S3_CAPTURED_TRAFFIC_DESCRIPTION,
        status: "ok",
        children: [
            scalarNode([...rootPath, "s3Uri"], "s3Uri", value?.s3Uri, "S3 URI of a gzipped traffic export produced by kafkaExport.sh.", true),
            scalarNode([...rootPath, "awsRegion"], "awsRegion", value?.awsRegion, "AWS region of the S3 bucket holding the export.", true),
            scalarNode([...rootPath, "endpoint"], "endpoint", value?.endpoint ?? "", "Override the S3 endpoint URL.", false, SOURCE_ENDPOINT_HINT),
            scalarNode([...rootPath, "kafka"], "kafka", value?.kafka ?? "default", "Label of the Kafka cluster to load captured traffic into. Must match a key in kafkaClusterConfiguration.", false, referenceHint(CAPTURE_KAFKA_HINT, ctx.kafkaOptions)),
            scalarNode([...rootPath, "kafkaTopic"], "kafkaTopic", value?.kafkaTopic ?? "", "Kafka topic name to load captured traffic into. If empty, defaults to the s3Source name.", false, CAPTURE_KAFKA_TOPIC_HINT),
            scalarNode([...rootPath, "sourceLabel"], "sourceLabel", value?.sourceLabel, "Label of the source cluster this dump was originally captured from.", true),
        ],
    });
}

function trafficReplayNode(name: string, value: any, ctx: EditContext): EditNode {
    const rootPath = ["traffic", "replayers", name];
    return finalizeNode({
        id: `edit:${rootPath.join(".")}`,
        path: rootPath,
        label: `traffic replay: ${name}`,
        valueKind: "object",
        description: REPLAYER_DESCRIPTION,
        status: "ok",
        children: [
            scalarNode([...rootPath, "fromCapturedTraffic"], "fromCapturedTraffic", value?.fromCapturedTraffic, "Name of the captured-traffic source to replay from. Must match a key in either traffic.proxies or traffic.s3Sources.", true, referenceHint(REPLAYER_CAPTURED_TRAFFIC_HINT, ctx.capturedTrafficOptions)),
            scalarNode([...rootPath, "toTarget"], "toTarget", value?.toTarget, "Name of the target cluster to replay traffic to. Must match a key in targetClusters.", true, referenceHint(REPLAYER_TARGET_HINT, ctx.targetOptions)),
        ],
    });
}

function trafficGroupNode(traffic: any, ctx: EditContext): EditNode {
    const proxyChildren = Object.entries(traffic?.proxies ?? {})
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([name, value]) => captureProxyNode(name, value, ctx));
    proxyChildren.push(addRow(["traffic", "proxies"], "capture proxy", "Create a capture proxy configuration in pending workflow YAML.", true, recordKeyHint(TRAFFIC_PROXIES_RECORD_HINT)));

    const s3SourceChildren = Object.entries(traffic?.s3Sources ?? {})
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([name, value]) => s3CapturedTrafficSourceNode(name, value, ctx));
    s3SourceChildren.push(addRow(["traffic", "s3Sources"], "S3 captured traffic source", "Create a pre-recorded traffic source from an S3 archive in pending workflow YAML.", true, recordKeyHint(TRAFFIC_S3_SOURCES_RECORD_HINT)));

    const replayChildren = Object.entries(traffic?.replayers ?? {})
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([name, value]) => trafficReplayNode(name, value, ctx));
    replayChildren.push(addRow(["traffic", "replayers"], "traffic replay", "Create a traffic replay configuration in pending workflow YAML.", true, recordKeyHint(TRAFFIC_REPLAYERS_RECORD_HINT)));

    return finalizeNode({
        id: "edit:traffic",
        path: ["traffic"],
        label: "Live Traffic Migration",
        valueKind: "object",
        description: TRAFFIC_DESCRIPTION,
        status: "ok",
        children: [
            finalizeNode({
                id: "edit:traffic.proxies",
                path: ["traffic", "proxies"],
                label: "Capture",
                valueKind: "record",
                description: "Capture proxies that receive source traffic and write it to Kafka.",
                inputHint: TRAFFIC_PROXIES_RECORD_HINT,
                status: "ok",
                children: proxyChildren,
            }),
            finalizeNode({
                id: "edit:traffic.s3Sources",
                path: ["traffic", "s3Sources"],
                label: "S3 Captured Traffic",
                valueKind: "record",
                description: "Pre-recorded traffic archives loaded from S3 into Kafka for replay.",
                inputHint: TRAFFIC_S3_SOURCES_RECORD_HINT,
                status: "ok",
                children: s3SourceChildren,
            }),
            finalizeNode({
                id: "edit:traffic.replayers",
                path: ["traffic", "replayers"],
                label: "Replay",
                valueKind: "record",
                description: "Traffic replayers that consume captured traffic and replay it to targets.",
                inputHint: TRAFFIC_REPLAYERS_RECORD_HINT,
                status: "ok",
                children: replayChildren,
            }),
        ],
    });
}

function snapshotMigrationNode(index: number, value: any, ctx: EditContext): EditNode {
    const rootPath = ["snapshotMigrationConfigs", String(index)];
    const fromSource = value?.fromSource ?? "";
    const toTarget = value?.toTarget ?? "";
    return finalizeNode({
        id: `edit:${rootPath.join(".")}`,
        path: rootPath,
        label: `snapshot migration: ${fromSource || "<source>"} -> ${toTarget || "<target>"}`,
        valueKind: "object",
        description: SNAPSHOT_MIGRATION_DESCRIPTION,
        status: "ok",
        children: [
            scalarNode([...rootPath, "fromSource"], "fromSource", fromSource, "Label of the source cluster to migrate from. Must match a key in sourceClusters.", true, referenceHint(SNAPSHOT_SOURCE_HINT, ctx.sourceOptions)),
            scalarNode([...rootPath, "toTarget"], "toTarget", toTarget, "Label of the target cluster to migrate to. Must match a key in targetClusters.", true, referenceHint(SNAPSHOT_TARGET_HINT, ctx.targetOptions)),
        ],
    });
}

function snapshotMigrationGroupNode(configs: any[] | undefined, ctx: EditContext): EditNode {
    const path = ["snapshotMigrationConfigs"];
    const children = (configs ?? []).map((value, index) => snapshotMigrationNode(index, value, ctx));
    children.push(addRow(path, "snapshot migration", "Create a snapshot migration configuration in pending workflow YAML.", false));
    return finalizeNode({
        id: `edit:${path.join(".")}`,
        path,
        label: "Snapshot Migrations",
        valueKind: "array",
        description: "List of snapshot-based migration configurations.",
        inputHint: SNAPSHOT_MIGRATION_ARRAY_HINT,
        status: "ok",
        children,
    });
}

function clusterNode(kind: "source" | "target", name: string, value: any): EditNode {
    const rootPath = [kind === "source" ? "sourceClusters" : "targetClusters", name];
    const children: EditNode[] = [
        scalarNode([...rootPath, "endpoint"], "endpoint", value?.endpoint, kind === "target"
            ? "HTTP(S) endpoint URL for the target cluster (e.g. 'https://target-cluster:9200/'). Required for target clusters."
            : "HTTP(S) endpoint URL for the cluster (e.g. 'https://my-cluster:9200/'). Leave empty if the cluster is not directly accessible or will be accessed through a proxy.",
        kind === "target",
        kind === "target" ? TARGET_ENDPOINT_HINT : SOURCE_ENDPOINT_HINT),
        booleanNode([...rootPath, "allowInsecure"], "allowInsecure", value?.allowInsecure, "When true, disables TLS certificate verification when connecting to the cluster. Use only for development or self-signed certificates."),
    ];
    if (kind === "source") {
        children.push(scalarNode([...rootPath, "version"], "version", value?.version, "Cluster version string in '<ENGINE> <VERSION>' format. Examples: 'ES 7.10.2', 'OS 2.11.0'.", true, VERSION_INPUT_HINT));
    }
    children.push(authNode([...rootPath, "authConfig"], value?.authConfig));
    if (kind === "source") {
        children.push(snapshotInfoNode([...rootPath, "snapshotInfo"], value?.snapshotInfo));
    }

    return finalizeNode({
        id: `edit:${rootPath.join(".")}`,
        path: rootPath,
        label: `${kind}: ${name}`,
        valueKind: "object",
        description: kind === "source" ? SOURCE_CLUSTER_DESCRIPTION : TARGET_CLUSTER_DESCRIPTION,
        descriptionShort: CLUSTER_DESCRIPTION,
        status: "ok",
        children,
    });
}

function addRow(
    path: string[],
    label: string,
    description: string,
    requiresName = true,
    inputHint?: EditInputHint
): EditNode {
    return finalizeNode({
        id: `edit:${path.join(".")}:add`,
        path,
        label: `+ Add ${label}`,
        valueKind: "command",
        description,
        inputHint,
        validation: validationFromHint(inputHint),
        command: {requiresName},
        status: "ok",
    });
}

function clusterGroupNode(kind: "source" | "target", config: Record<string, any> | undefined): EditNode {
    const path = kind === "source" ? ["sourceClusters"] : ["targetClusters"];
    const children = Object.entries(config ?? {})
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([name, value]) => clusterNode(kind, name, value));
    children.push(addRow(path, `${kind} cluster`, kind === "source"
        ? "Create a new source cluster entry in pending workflow YAML."
        : "Create a new target cluster entry in pending workflow YAML.",
        true,
        recordKeyHint(kind === "source" ? SOURCE_RECORD_HINT : TARGET_RECORD_HINT)));

    return finalizeNode({
        id: `edit:${path.join(".")}`,
        path,
        label: kind === "source" ? "Source Clusters" : "Target Clusters",
        valueKind: "record",
        description: kind === "source" ? SOURCE_CLUSTERS_DESCRIPTION : TARGET_CLUSTERS_DESCRIPTION,
        inputHint: kind === "source" ? SOURCE_RECORD_HINT : TARGET_RECORD_HINT,
        status: "ok",
        children,
    });
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

export function validationForConfig(config: unknown): EditStateV1["validation"] {
    try {
        new MigrationConfigTransformer().validateInput(config);
        return {valid: true, errors: []};
    } catch (error) {
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
        return {
            valid: false,
            errors: [String(error)],
            diagnostics: [{severity: "error", message: String(error), path: []}],
        };
    }
}

export function buildEditStateFromObject(config: any): EditStateV1 {
    const ctx = buildEditContext(config);
    const nodes = [
        clusterGroupNode("source", config?.sourceClusters),
        clusterGroupNode("target", config?.targetClusters),
        kafkaGroupNode(config?.kafkaClusterConfiguration),
        trafficGroupNode(config?.traffic, ctx),
        snapshotMigrationGroupNode(config?.snapshotMigrationConfigs, ctx),
    ];
    const validation = validationForConfig(config);
    applyValidationDiagnostics(nodes, validation.diagnostics ?? []);
    return {
        formatVersion: 1,
        provenance: {
            source: "pending-yaml",
            lossy: false,
            warnings: [],
        },
        nodes,
        pendingSubmitChanges: [],
        submittedRolloutChanges: [],
        policyPreview: [],
        validation,
    };
}

function ensureContainer(parent: any, key: string): Record<string, unknown> {
    if (!parent[key] || typeof parent[key] !== "object" || Array.isArray(parent[key])) {
        parent[key] = {};
    }
    return parent[key];
}

function isArrayIndex(part: string): boolean {
    const index = Number(part);
    return Number.isInteger(index) && index >= 0;
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

function kafkaConfigForVariant(existing: any, variant: unknown): unknown {
    if (variant === "autoCreate") {
        return {autoCreate: existing?.autoCreate ?? {}};
    }
    if (variant === "existing") {
        return {existing: existing?.existing ?? {}};
    }
    throw new Error(`Unknown Kafka cluster variant: ${String(variant)}`);
}

function setAtPath(config: any, path: string[], value: unknown): void {
    const {parent, key} = parentAtPath(config, path);
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
        const replacement = kafkaConfigForVariant(parent, value);
        for (const existingKey of Object.keys(parent)) {
            delete parent[existingKey];
        }
        Object.assign(parent, replacement);
        return;
    }
    parent[key] = value;
}

function removeAtPath(config: any, path: string[]): void {
    if (path.length < 2) {
        throw new Error("Only named config entries can be removed");
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

function defaultConfigForPath(path: string[]): Record<string, unknown> {
    const key = path.join(".");
    if (key === "sourceClusters") {
        return {
            endpoint: "",
            allowInsecure: false,
            version: "",
        };
    }
    if (key === "targetClusters") {
        return {
            endpoint: "",
            allowInsecure: false,
        };
    }
    if (key === "kafkaClusterConfiguration") {
        return {autoCreate: {}};
    }
    if (key === "traffic.proxies") {
        return {source: ""};
    }
    if (key === "traffic.s3Sources") {
        return {s3Uri: "", awsRegion: "", sourceLabel: ""};
    }
    if (key === "traffic.replayers") {
        return {fromCapturedTraffic: "", toTarget: ""};
    }
    if (key === "snapshotMigrationConfigs") {
        return {fromSource: "", toTarget: "", perSnapshotConfig: {}};
    }
    throw new Error(`Add is not supported at path ${path.join(".")}`);
}

function addAtPath(config: any, path: string[], value: unknown): void {
    if (path.length === 1 && path[0] === "snapshotMigrationConfigs") {
        if (!Array.isArray(config.snapshotMigrationConfigs)) {
            config.snapshotMigrationConfigs = [];
        }
        config.snapshotMigrationConfigs.push(defaultConfigForPath(path));
        return;
    }

    const name = typeof value === "object" && value !== null && "name" in value
        ? String((value as { name: unknown }).name).trim()
        : "";
    if (!name) {
        throw new Error("Add operation requires a non-empty name");
    }
    let parent = config;
    for (const part of path) {
        parent = ensureContainer(parent, part);
    }
    if (name in parent) {
        throw new Error(`Config entry already exists at ${[...path, name].join(".")}`);
    }
    parent[name] = defaultConfigForPath(path);
}

export function applyEditOperation(config: any, operation: EditOperation): any {
    const nextConfig = config && typeof config === "object" ? structuredClone(config) : {};
    if (operation.op === "set") {
        setAtPath(nextConfig, operation.path, operation.value);
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

function withConsoleDiagnosticsOnStderr<T>(callback: () => T): T {
    const originalLog = console.log;
    const originalInfo = console.info;
    const originalWarn = console.warn;
    const redirect = (...args: unknown[]) => console.error(...args);

    console.log = redirect;
    console.info = redirect;
    console.warn = redirect;
    try {
        return callback();
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
        const editState = withConsoleDiagnosticsOnStderr(() => buildEditStateFromObject(config));
        process.stdout.write(JSON.stringify(editState, null, 2));
        return;
    }

    const operationFlag = args.shift();
    const operationPath = args.shift();
    if (operationFlag !== "--operation" || !operationPath || args.length > 0) {
        usage();
    }
    const operation = await parseYaml(operationPath) as EditOperation;
    const result = withConsoleDiagnosticsOnStderr(() => applyEditOperationToObject(config, operation));
    process.stdout.write(JSON.stringify(result, null, 2));
}

if (require.main === module && !process.env.SUPPRESS_AUTO_LOAD) {
    main().catch(error => {
        console.error(error);
        process.exit(1);
    });
}
