import {
    CLUSTER_CONFIG,
    HTTP_AUTH_BASIC,
    HTTP_AUTH_MTLS,
    HTTP_AUTH_SIGV4,
    SOURCE_CLUSTER_CONFIG,
    SOURCE_CLUSTERS_MAP,
    TARGET_CLUSTER_CONFIG,
    TARGET_CLUSTERS_MAP,
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
    diagnostics?: EditDiagnostic[];
    variants?: {
        label: string;
        value: unknown;
        description?: string;
        childSchema?: EditNode[];
    }[];
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
const AUTH_DESCRIPTION = "Authentication configuration for connecting to the cluster. Supports HTTP Basic (Kubernetes Secret), AWS SigV4, or mutual TLS.";

function descriptionOf(schema: {description?: string}): string | undefined {
    return schema.description;
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

function highestStatus(a: EditNodeStatus, b: EditNodeStatus): EditNodeStatus {
    return STATUS_RANK[a] >= STATUS_RANK[b] ? a : b;
}

function scalarNode(
    path: string[],
    key: string,
    value: unknown,
    description: string,
    required = false
): EditNode {
    const missing = required && (value === undefined || value === null || value === "");
    const diagnostics: EditDiagnostic[] = missing
        ? [{severity: "required", message: `${key} is required.`, path}]
        : [];
    return finalizeNode({
        id: `edit:${path.join(".")}`,
        path,
        label: `${key}: ${value === undefined || value === null || value === "" ? "<required>" : String(value)}`,
        value,
        valueKind: typeof value === "boolean" ? "boolean" : "scalar",
        description,
        required,
        status: missing ? "required" : "ok",
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
                true
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
            scalarNode([...path, "mtls", "clientSecretName"], "clientSecretName", authConfig?.mtls?.clientSecretName, "Name of a Kubernetes TLS Secret containing the client certificate and private key for mutual TLS authentication.", true),
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

function clusterNode(kind: "source" | "target", name: string, value: any): EditNode {
    const rootPath = [kind === "source" ? "sourceClusters" : "targetClusters", name];
    const children: EditNode[] = [
        scalarNode([...rootPath, "endpoint"], "endpoint", value?.endpoint, kind === "target"
            ? "HTTP(S) endpoint URL for the target cluster (e.g. 'https://target-cluster:9200/'). Required for target clusters."
            : "HTTP(S) endpoint URL for the cluster (e.g. 'https://my-cluster:9200/'). Leave empty if the cluster is not directly accessible or will be accessed through a proxy.",
        kind === "target"),
        booleanNode([...rootPath, "allowInsecure"], "allowInsecure", value?.allowInsecure, "When true, disables TLS certificate verification when connecting to the cluster. Use only for development or self-signed certificates."),
    ];
    if (kind === "source") {
        children.push(scalarNode([...rootPath, "version"], "version", value?.version, "Cluster version string in '<ENGINE> <VERSION>' format. Examples: 'ES 7.10.2', 'OS 2.11.0'.", true));
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

function addRow(path: string[], label: string, description: string): EditNode {
    return finalizeNode({
        id: `edit:${path.join(".")}:add`,
        path,
        label: `+ Add ${label}`,
        valueKind: "command",
        description,
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
        : "Create a new target cluster entry in pending workflow YAML."));

    return finalizeNode({
        id: `edit:${path.join(".")}`,
        path,
        label: kind === "source" ? "Source Clusters" : "Target Clusters",
        valueKind: "record",
        description: kind === "source" ? SOURCE_CLUSTERS_DESCRIPTION : TARGET_CLUSTERS_DESCRIPTION,
        status: "ok",
        children,
    });
}

function validationForConfig(config: unknown): EditStateV1["validation"] {
    try {
        new MigrationConfigTransformer().validateInput(config);
        return {valid: true, errors: []};
    } catch (error) {
        if (error instanceof InputValidationError) {
            return {valid: false, errors: [formatInputValidationError(error)]};
        }
        if (error instanceof z.ZodError) {
            return {valid: false, errors: error.issues.map(issue => `${issue.path.join(".")}: ${issue.message}`)};
        }
        return {valid: false, errors: [String(error)]};
    }
}

export function buildEditStateFromObject(config: any): EditStateV1 {
    return {
        formatVersion: 1,
        provenance: {
            source: "pending-yaml",
            lossy: false,
            warnings: [],
        },
        nodes: [
            clusterGroupNode("source", config?.sourceClusters),
            clusterGroupNode("target", config?.targetClusters),
        ],
        pendingSubmitChanges: [],
        submittedRolloutChanges: [],
        policyPreview: [],
        validation: validationForConfig(config),
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
    for (const part of path.slice(0, -1)) {
        parent = ensureContainer(parent, part);
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
    delete parent[key];
}

function defaultClusterConfig(path: string[]): Record<string, unknown> {
    if (path.length !== 1 || (path[0] !== "sourceClusters" && path[0] !== "targetClusters")) {
        throw new Error(`Add is not supported at path ${path.join(".")}`);
    }
    if (path[0] === "sourceClusters") {
        return {
            endpoint: "",
            allowInsecure: false,
            version: "",
        };
    }
    return {
        endpoint: "",
        allowInsecure: false,
    };
}

function addAtPath(config: any, path: string[], value: unknown): void {
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
    parent[name] = defaultClusterConfig(path);
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
        process.stdout.write(JSON.stringify(buildEditStateFromObject(config), null, 2));
        return;
    }

    const operationFlag = args.shift();
    const operationPath = args.shift();
    if (operationFlag !== "--operation" || !operationPath || args.length > 0) {
        usage();
    }
    const operation = await parseYaml(operationPath) as EditOperation;
    process.stdout.write(JSON.stringify(applyEditOperationToObject(config, operation), null, 2));
}

if (require.main === module && !process.env.SUPPRESS_AUTO_LOAD) {
    main().catch(error => {
        console.error(error);
        process.exit(1);
    });
}
