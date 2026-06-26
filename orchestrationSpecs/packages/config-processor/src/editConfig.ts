import {
    CLUSTER_CONFIG,
    CLUSTER_VERSION_STRING,
    CAPTURE_CONFIG,
    EffectiveDefaultHint,
    ExternalRefHint,
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
    PROXY_TLS_CLIENT_AUTH_CONFIG,
    PROXY_TLS_CONFIG,
    REPLAYER_CONFIG,
    S3_CAPTURED_TRAFFIC_SOURCE,
    SOURCE_CLUSTER_CONFIG,
    SOURCE_CLUSTERS_MAP,
    TARGET_CLUSTER_CONFIG,
    TARGET_CLUSTERS_MAP,
    TRAFFIC_CONFIG,
    UiHint,
    UiTextFormat,
    USER_PROXY_OPTIONS,
    USER_PROXY_PROCESS_OPTION_KEYS,
    USER_PROXY_WORKFLOW_OPTION_KEYS,
    getDescription,
    loadUnifiedSchema,
    unwrapSchema as unwrapSchemaWithPipes,
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
    valueType?: "string" | "number" | "boolean";
    valueKind: EditNodeValueKind;
    presence?: "required" | "optional";
    expert?: boolean;
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
    externalRef?: ExternalRefHint;
    effectiveDefault?: EffectiveDefaultHint;
    validation?: EditNodeValidation;
    diagnostics?: EditDiagnostic[];
    collapsed?: boolean;
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
    | { op: "unset"; path: string[] }
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
const BASIC_SECRET_EXTERNAL_REF = externalRefAt(HTTP_AUTH_BASIC, ["basic", "secretName"]);
const MTLS_CLIENT_SECRET_NAME_HINT = uiHintAt(HTTP_AUTH_MTLS, ["mtls", "clientSecretName"]) ?? K8S_NAME_INPUT_HINT;
const PROXY_TLS_CERT_MANAGER_SCHEMA = discriminatedUnionOption(PROXY_TLS_CONFIG, "mode", "certManager");
const PROXY_TLS_ISSUER_EXTERNAL_REF = externalRefAt(PROXY_TLS_CERT_MANAGER_SCHEMA, ["issuerRef"]);
const PROXY_TLS_EXISTING_SECRET_SCHEMA = discriminatedUnionOption(PROXY_TLS_CONFIG, "mode", "existingSecret");
const PROXY_TLS_PLAINTEXT_SCHEMA = discriminatedUnionOption(PROXY_TLS_CONFIG, "mode", "plaintext");
const PROXY_TLS_SECRET_NAME_HINT = uiHintAt(PROXY_TLS_EXISTING_SECRET_SCHEMA, ["secretName"]) ?? K8S_NAME_INPUT_HINT;
const PROXY_TLS_SECRET_EXTERNAL_REF = externalRefAt(PROXY_TLS_EXISTING_SECRET_SCHEMA, ["secretName"]);
const PROXY_CLIENT_AUTH_CONSOLE_SECRET_HINT = uiHintAt(PROXY_TLS_CLIENT_AUTH_CONFIG, ["consoleClientSecretName"]) ?? K8S_NAME_INPUT_HINT;
const PROXY_CLIENT_AUTH_CONSOLE_SECRET_EXTERNAL_REF = externalRefAt(PROXY_TLS_CLIENT_AUTH_CONFIG, ["consoleClientSecretName"]);
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

function descriptionOf(schema: any): string | undefined {
    return getDescription(schema) ?? schema?.description;
}

function unwrapSchema(schema: any): any {
    return schema ? unwrapSchemaWithPipes(schema) : schema;
}

function uiHintOf(schema: any): EditInputHint | undefined {
    const direct = schema?.meta?.() as FieldMeta | undefined;
    const unwrapped = unwrapSchema(schema);
    const inner = unwrapped === schema ? undefined : unwrapped?.meta?.() as FieldMeta | undefined;
    const hint = direct?.uiHint ?? inner?.uiHint;
    return hint ? {...hint} as EditInputHint : undefined;
}

function externalRefOf(schema: any): ExternalRefHint | undefined {
    const direct = schema?.meta?.() as FieldMeta | undefined;
    const unwrapped = unwrapSchema(schema);
    const inner = unwrapped === schema ? undefined : unwrapped?.meta?.() as FieldMeta | undefined;
    const hint = direct?.externalRef ?? inner?.externalRef;
    return hint ? structuredClone(hint) : undefined;
}

function effectiveDefaultOf(schema: any): EffectiveDefaultHint | undefined {
    const direct = schema?.meta?.() as FieldMeta | undefined;
    const unwrapped = unwrapSchema(schema);
    const inner = unwrapped === schema ? undefined : unwrapped?.meta?.() as FieldMeta | undefined;
    const hint = direct?.effectiveDefault ?? inner?.effectiveDefault;
    return hint ? structuredClone(hint) : undefined;
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

function externalRefAt(schema: any, path: string[]): ExternalRefHint | undefined {
    let current = schema;
    for (const part of path) {
        const unwrapped = unwrapSchema(current);
        current = unwrapped?.shape?.[part];
        if (!current) {
            return undefined;
        }
    }
    return externalRefOf(current);
}

function discriminatedUnionOption(schema: any, discriminator: string, value: unknown): any | undefined {
    const unwrapped = unwrapSchema(schema);
    const options = Array.isArray(unwrapped?.options) ? unwrapped.options : [];
    return options.find((option: any) => {
        const shape = unwrapSchema(option)?.shape ?? {};
        return literalValues(shape[discriminator]).has(value);
    });
}

function literalValues(schema: any): Set<unknown> {
    const literal = unwrapSchema(schema);
    if (literal?.values instanceof Set) {
        return literal.values;
    }
    if (Object.hasOwn(literal ?? {}, "value")) {
        return new Set([literal.value]);
    }
    return new Set();
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

function isRequiredSchema(schema: any): boolean {
    const parsed = schema?.safeParse?.(undefined);
    return parsed ? !parsed.success : false;
}

function defaultValueForSchema(schema: any): unknown {
    const parsed = schema?.safeParse?.(undefined);
    return parsed?.success ? parsed.data : undefined;
}

function schemaDescription(schema: any): string {
    return descriptionOf(schema) ?? descriptionOf(unwrapSchema(schema)) ?? "";
}

function isExpertDescription(description: string): boolean {
    return /^\s*\[Expert\]/i.test(description) || /^\s*Expert\b/i.test(description);
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
    return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function schemaConstructorName(schema: any): string {
    return String(unwrapSchema(schema)?.constructor?.name ?? "");
}

function schemaScalarType(schema: any): EditNode["valueType"] | undefined {
    const name = schemaConstructorName(schema);
    if (name === "ZodNumber") {
        return "number";
    }
    if (name === "ZodBoolean") {
        return "boolean";
    }
    if (name === "ZodString" || name === "ZodEnum" || name === "ZodLiteral") {
        return "string";
    }
    return undefined;
}

function schemaContainerKind(schema: any): "array" | "object" | undefined {
    const name = schemaConstructorName(schema);
    if (name === "ZodArray") {
        return "array";
    }
    if (name === "ZodObject" || name === "ZodRecord" || name === "ZodUnion" || name === "ZodDiscriminatedUnion") {
        return "object";
    }
    return undefined;
}

function isGenericRecordSchema(schema: any): boolean {
    return schemaConstructorName(schema) === "ZodRecord";
}

type JsonSchema = Record<string, any>;

let cachedUnifiedSchema: JsonSchema | undefined;
let cachedUnifiedSchemaKey: string | undefined;

function unifiedSchema(): JsonSchema | undefined {
    const cacheKey = process.env.MIGRATION_UNIFIED_SCHEMA_PATH ?? "";
    if (cachedUnifiedSchema !== undefined && cachedUnifiedSchemaKey === cacheKey) {
        return cachedUnifiedSchema;
    }
    try {
        cachedUnifiedSchema = loadUnifiedSchema().schema as JsonSchema;
        cachedUnifiedSchemaKey = cacheKey;
    } catch {
        return undefined;
    }
    return cachedUnifiedSchema;
}

function isJsonSchemaObject(value: unknown): value is JsonSchema {
    return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function resolveJsonSchemaRef(schema: JsonSchema | undefined, root: JsonSchema | undefined = unifiedSchema()): JsonSchema | undefined {
    if (!schema) {
        return undefined;
    }
    if (typeof schema.$ref !== "string" || !schema.$ref.startsWith("#/")) {
        return schema;
    }
    const refPath = schema.$ref
        .slice(2)
        .split("/")
        .map(part => part.replace(/~1/g, "/").replace(/~0/g, "~"));
    let current: any = root;
    for (const part of refPath) {
        current = current?.[part];
        if (!current) {
            return schema;
        }
    }
    const resolved = isJsonSchemaObject(current) ? current : schema;
    return {
        ...resolved,
        description: schema.description ?? resolved.description,
    };
}

function jsonSchemaBranches(schema: JsonSchema | undefined): JsonSchema[] {
    const resolved = resolveJsonSchemaRef(schema);
    const branches = resolved?.oneOf ?? resolved?.anyOf;
    return Array.isArray(branches)
        ? branches.filter(isJsonSchemaObject).map(branch => resolveJsonSchemaRef(branch) ?? branch)
        : [];
}

function jsonSchemaProperties(schema: JsonSchema | undefined): Record<string, JsonSchema> {
    const resolved = resolveJsonSchemaRef(schema);
    return isJsonSchemaObject(resolved?.properties) ? resolved.properties : {};
}

function jsonSchemaRequired(schema: JsonSchema | undefined): Set<string> {
    const required = resolveJsonSchemaRef(schema)?.required;
    return new Set(Array.isArray(required) ? required.map(String) : []);
}

function jsonSchemaType(schema: JsonSchema | undefined): string | undefined {
    const type = resolveJsonSchemaRef(schema)?.type;
    return Array.isArray(type) ? type.find(item => item !== "null") : type;
}

function jsonSchemaProperty(schema: JsonSchema | undefined, key: string): JsonSchema | undefined {
    return jsonSchemaProperties(schema)[key];
}

function jsonSchemaChildAtPath(schema: JsonSchema | undefined, path: string[]): JsonSchema | undefined {
    const resolved = resolveJsonSchemaRef(schema);
    if (!resolved || !path.length) {
        return resolved;
    }

    const [part, ...rest] = path;
    const direct = jsonSchemaProperty(resolved, part);
    if (direct) {
        return jsonSchemaChildAtPath(direct, rest);
    }

    const branch = jsonSchemaBranches(resolved).find(item => Boolean(jsonSchemaProperty(item, part)));
    if (branch) {
        return jsonSchemaChildAtPath(branch, path);
    }

    const additional = resolved.additionalProperties;
    if (isJsonSchemaObject(additional)) {
        return jsonSchemaChildAtPath(additional, rest);
    }

    const items = resolveJsonSchemaRef(resolved.items);
    if (items && isArrayIndex(part)) {
        return jsonSchemaChildAtPath(items, rest);
    }

    return undefined;
}

function jsonSchemaForConfigPath(path: string[]): JsonSchema | undefined {
    return jsonSchemaChildAtPath(unifiedSchema(), path);
}

function jsonSchemaDescription(schema: JsonSchema | undefined): string {
    return String(resolveJsonSchemaRef(schema)?.description ?? "");
}

function jsonScalarValueType(schema: JsonSchema | undefined): EditNode["valueType"] | undefined {
    const type = jsonSchemaType(schema);
    if (type === "number" || type === "integer") {
        return "number";
    }
    if (type === "boolean") {
        return "boolean";
    }
    if (type === "string") {
        return "string";
    }
    return undefined;
}

function jsonSchemaEnumValues(schema: JsonSchema | undefined): unknown[] {
    const resolved = resolveJsonSchemaRef(schema);
    if (Object.hasOwn(resolved ?? {}, "const")) {
        return [resolved!.const];
    }
    return Array.isArray(resolved?.enum) ? resolved.enum : [];
}

function defaultJsonValueForSchema(schema: JsonSchema | undefined): unknown {
    const resolved = resolveJsonSchemaRef(schema);
    if (!resolved) {
        return {};
    }
    if (resolved.default !== undefined) {
        return structuredClone(resolved.default);
    }
    const enumValues = jsonSchemaEnumValues(resolved);
    if (enumValues.length === 1) {
        return enumValues[0];
    }
    if (enumValues.length > 1) {
        return "";
    }
    const type = jsonSchemaType(resolved);
    if (type === "array") {
        return [];
    }
    if (type === "object" || jsonSchemaBranches(resolved).length > 0) {
        return {};
    }
    if (type === "boolean") {
        return false;
    }
    return "";
}

function jsonSchemaInputHint(schema: JsonSchema | undefined): EditInputHint | undefined {
    const resolved = resolveJsonSchemaRef(schema);
    const pattern = typeof resolved?.pattern === "string" ? resolved.pattern : undefined;
    return pattern ? textHint(pattern, "Value does not match the expected format.") : undefined;
}

function jsonSchemaDiscriminator(schema: JsonSchema | undefined): string | undefined {
    const branches = jsonSchemaBranches(schema);
    if (!branches.length) {
        return undefined;
    }
    const candidates = new Set<string>();
    for (const branch of branches) {
        for (const [key, childSchema] of Object.entries(jsonSchemaProperties(branch))) {
            if (jsonSchemaEnumValues(childSchema).length === 1) {
                candidates.add(key);
            }
        }
    }
    return [...candidates].find(candidate =>
        branches.every(branch => jsonSchemaEnumValues(jsonSchemaProperty(branch, candidate)).length === 1)
    );
}

function jsonSchemaUnionNode(
    path: string[],
    key: string,
    schema: JsonSchema,
    value: unknown,
    hasValue: boolean,
    required: boolean,
    expert: boolean,
    presence: EditNode["presence"],
): EditNode | undefined {
    const discriminator = jsonSchemaDiscriminator(schema);
    if (!discriminator) {
        return undefined;
    }

    const branches = jsonSchemaBranches(schema);
    const selectedValue = isPlainObject(value) ? value[discriminator] : undefined;
    const selectedBranch = branches.find(branch => jsonSchemaEnumValues(jsonSchemaProperty(branch, discriminator))[0] === selectedValue);
    const unset = !required && !hasValue && selectedValue === undefined;
    const variants = [
        ...(!required ? [{label: "unset", value: "unset", description: "Remove this optional configuration."}] : []),
        ...branches.map(branch => {
            const branchValue = jsonSchemaEnumValues(jsonSchemaProperty(branch, discriminator))[0];
            return {
                label: String(branchValue),
                value: branchValue,
                description: jsonSchemaDescription(branch),
            };
        }),
    ];
    const unknown = selectedValue !== undefined && !selectedBranch;

    return finalizeNode({
        id: `edit:${path.join(".")}`,
        path,
        label: `${key}: < ${unset ? "unset" : selectedValue ?? "required"} >`,
        value: unset ? "unset" : selectedValue,
        valueKind: "union",
        presence,
        expert,
        description: jsonSchemaDescription(schema),
        status: unknown ? "error" : required && selectedValue === undefined ? "required" : "ok",
        diagnostics: unknown ? [{
            severity: "error",
            message: `Unknown ${key} variant. Expected ${variants.map(variant => variant.value).join(" or ")}.`,
            path,
        }] : [],
        variants,
        children: selectedBranch
            ? jsonSchemaObjectChildren(path, selectedBranch, value, new Set([discriminator]))
            : isPlainObject(value) ? objectChildrenFromValue(path, value) : [],
    });
}

function jsonEnumNode(
    path: string[],
    key: string,
    schema: JsonSchema,
    value: unknown,
    required: boolean,
    expert: boolean,
    presence: EditNode["presence"],
): EditNode {
    const values = jsonSchemaEnumValues(schema);
    const unset = value === undefined || value === null || value === "";
    return finalizeNode({
        id: `edit:${path.join(".")}`,
        path,
        label: `${key}: < ${unset ? (required ? "required" : "unset") : String(value)} >`,
        value: unset && !required ? "unset" : value,
        valueKind: "union",
        presence,
        expert,
        description: jsonSchemaDescription(schema),
        status: required && unset ? "required" : "ok",
        variants: [
            ...(!required ? [{label: "unset", value: "unset", description: "Remove this optional value."}] : []),
            ...values.map(option => ({label: String(option), value: option})),
        ],
    });
}

function jsonSchemaObjectChildren(
    rootPath: string[],
    schema: JsonSchema,
    value: unknown,
    excludedKeys: Set<string> = new Set(),
): EditNode[] {
    const requiredKeys = jsonSchemaRequired(schema);
    const objectValue = isPlainObject(value) ? value : {};
    return Object.entries(jsonSchemaProperties(schema))
        .filter(([key]) => !excludedKeys.has(key))
        .map(([key, childSchema]) => jsonSchemaFieldNode(
            rootPath,
            key,
            childSchema,
            objectValue,
            requiredKeys.has(key),
        ));
}

function jsonSchemaArrayChildren(
    rootPath: string[],
    schema: JsonSchema,
    value: unknown,
): EditNode[] {
    const arrayValue = Array.isArray(value) ? value : [];
    const itemSchema = resolveJsonSchemaRef(schema.items);
    return [
        ...arrayValue.map((itemValue, index) => jsonSchemaArrayItemNode(rootPath, itemSchema, itemValue, index)),
        addRow(rootPath, "item", "Create a new array item in pending workflow YAML.", false),
    ];
}

function jsonSchemaArrayItemNode(
    rootPath: string[],
    schema: JsonSchema | undefined,
    value: unknown,
    index: number,
): EditNode {
    const key = String(index);
    const node = schema
        ? jsonSchemaFieldNode(rootPath, key, schema, {[key]: value}, true)
        : genericDisplayNode([...rootPath, key], key, value, "required", false, "");
    const label = stripBadge(node.label);
    const valueSuffix = label.includes(":") ? label.slice(label.indexOf(":")) : "";
    node.label = `item ${index + 1}${valueSuffix}`;
    node.collapsed = true;
    refreshNodeBadge(node);
    return node;
}

function jsonSchemaFieldNode(
    rootPath: string[],
    key: string,
    schema: JsonSchema,
    config: Record<string, unknown>,
    required = false,
): EditNode {
    const resolved = resolveJsonSchemaRef(schema) ?? schema;
    const path = [...rootPath, key];
    const hasValue = Object.hasOwn(config, key);
    const value = hasValue ? config[key] : resolved.default;
    const description = jsonSchemaDescription(resolved);
    const presence: EditNode["presence"] = required ? "required" : "optional";
    const expert = isExpertDescription(description);
    const unionNode = jsonSchemaUnionNode(path, key, resolved, value, hasValue, required, expert, presence);
    if (unionNode) {
        return unionNode;
    }
    if (jsonSchemaEnumValues(resolved).length > 0) {
        return jsonEnumNode(path, key, resolved, value, required, expert, presence);
    }
    const valueType = jsonScalarValueType(resolved);
    if (valueType === "boolean") {
        return booleanNode(path, key, value === true, description, expert, presence);
    }
    if (valueType === "number" || valueType === "string") {
        return scalarNode(path, key, value ?? "", description, required, jsonSchemaInputHint(resolved), valueType, expert, presence);
    }

    const containerKind = jsonSchemaType(resolved) === "array" ? "array" : "object";
    const childNodes = containerKind === "object"
        ? jsonSchemaObjectChildren(path, resolved, value)
        : jsonSchemaArrayChildren(path, resolved, value);
    if (childNodes.length || value === undefined || value === null || isPlainObject(value) || Array.isArray(value)) {
        return finalizeNode({
            id: `edit:${path.join(".")}`,
            path,
            label: `${key}: ${value === undefined || value === null ? (required ? "<required>" : "<unset>") : Array.isArray(value) ? `${value.length} item${value.length === 1 ? "" : "s"}` : isPlainObject(value) ? (Object.keys(value).length ? "configured" : "{}") : String(value)}`,
            value,
            valueKind: containerKind,
            presence,
            expert,
            description,
            required,
            status: required && (value === undefined || value === null) ? "required" : "ok",
            children: childNodes,
        });
    }

    return genericDisplayNode(path, key, value, presence, expert, description);
}

function schemaOptions(schema: any): any[] {
    const unwrapped = unwrapSchema(schema);
    const options = unwrapped?.options ?? unwrapped?._def?.options;
    return Array.isArray(options) ? options : [];
}

function schemaShape(schema: any): Record<string, any> | undefined {
    const shape = unwrapSchema(schema)?.shape;
    return isPlainObject(shape) ? shape : undefined;
}

interface SingleKeyUnionBranch {
    value: string;
    optionSchema: any;
    fieldSchema: any;
    description?: string;
}

function singleKeyUnionBranches(schema: any): SingleKeyUnionBranch[] {
    const options = schemaOptions(schema);
    if (!options.length) {
        return [];
    }
    const branches = options.map(optionSchema => {
        const shape = schemaShape(optionSchema);
        const keys = Object.keys(shape ?? {});
        if (keys.length !== 1) {
            return undefined;
        }
        const value = keys[0];
        return {
            value,
            optionSchema,
            fieldSchema: shape![value],
            description: schemaDescription(optionSchema),
        };
    });
    return branches.every(Boolean) ? branches as SingleKeyUnionBranch[] : [];
}

function selectedSingleKeyUnionBranch(
    branches: SingleKeyUnionBranch[],
    value: unknown,
    fallbackValue?: string,
): SingleKeyUnionBranch | undefined {
    if (isPlainObject(value)) {
        const presentBranch = branches.find(branch => Object.hasOwn(value, branch.value));
        if (presentBranch) {
            return presentBranch;
        }
    }
    return branches.find(branch => branch.value === fallbackValue) ?? branches[0];
}

function schemaObjectChildren(
    rootPath: string[],
    schema: any,
    value: unknown,
    excludedKeys: Set<string> = new Set(),
): EditNode[] {
    const shape = schemaShape(schema) ?? {};
    const config = isPlainObject(value) ? value : {};
    return Object.entries(shape)
        .filter(([key]) => !excludedKeys.has(key))
        .map(([key, fieldSchema]) => schemaFieldNode(rootPath, key, fieldSchema, config));
}

function singleKeyUnionMode(
    rootPath: string[],
    modeKey: string,
    schema: any,
    value: unknown,
    description: string | undefined,
    fallbackValue?: string,
): { modeNode: EditNode; branchChildren: EditNode[] } {
    const branches = singleKeyUnionBranches(schema);
    const selected = selectedSingleKeyUnionBranch(branches, value, fallbackValue);
    const selectedValue = selected?.value ?? "unknown";
    const branchValue = selected && isPlainObject(value) && isPlainObject(value[selected.value])
        ? value[selected.value]
        : {};
    const expectedValues = branches.map(branch => branch.value).join(" or ");
    const diagnostics: EditDiagnostic[] = selected
        ? []
        : [{severity: "error", message: `Unknown variant. Expected ${expectedValues}.`, path: rootPath}];

    return {
        modeNode: finalizeNode({
            id: `edit:${[...rootPath, modeKey].join(".")}`,
            path: [...rootPath, modeKey],
            label: `${modeKey}: < ${selectedValue} >`,
            value: selectedValue,
            valueKind: "union",
            description,
            status: selected ? "ok" : "error",
            diagnostics,
            variants: branches.map(branch => ({
                label: branch.value,
                value: branch.value,
                description: branch.description,
            })),
        }),
        branchChildren: selected
            ? schemaObjectChildren([...rootPath, selected.value], selected.fieldSchema, branchValue)
            : [],
    };
}

function discriminatorForSchema(schema: any): string | undefined {
    const discriminator = unwrapSchema(schema)?._def?.discriminator;
    return typeof discriminator === "string" ? discriminator : undefined;
}

interface DiscriminatedUnionBranch {
    value: unknown;
    optionSchema: any;
    description?: string;
}

function discriminatedUnionBranches(schema: any, discriminator: string): DiscriminatedUnionBranch[] {
    return schemaOptions(schema)
        .map(optionSchema => {
            const shape = schemaShape(optionSchema);
            const values = literalValues(shape?.[discriminator]);
            const [value] = values;
            if (value === undefined) {
                return undefined;
            }
            return {
                value,
                optionSchema,
                description: schemaDescription(optionSchema),
            };
        })
        .filter(Boolean) as DiscriminatedUnionBranch[];
}

function discriminatedUnionNode(
    path: string[],
    key: string,
    schema: any,
    value: unknown,
    hasValue: boolean,
    description: string,
    required: boolean,
    expert: boolean,
    presence: EditNode["presence"],
): EditNode | undefined {
    const discriminator = discriminatorForSchema(schema);
    if (!discriminator) {
        return undefined;
    }

    const branches = discriminatedUnionBranches(schema, discriminator);
    if (!branches.length) {
        return undefined;
    }

    const selectedValue = isPlainObject(value) ? value[discriminator] : undefined;
    const selected = branches.find(branch => branch.value === selectedValue);
    const hasSchemaDefault = defaultValueForSchema(schema) !== undefined;
    const effectiveDefault = effectiveDefaultOf(schema);
    const includeUnset = !required && !hasSchemaDefault;
    const unset = includeUnset && !hasValue && selectedValue === undefined;
    const unsetLabel = effectiveDefault?.label
        ? `default: ${effectiveDefault.label}`
        : "unset";
    const missing = required && selectedValue === undefined;
    const unknown = selectedValue !== undefined && !selected;
    const diagnostics: EditDiagnostic[] = [];
    if (missing) {
        diagnostics.push({severity: "required", message: `${key} is required.`, path});
    } else if (unknown) {
        diagnostics.push({
            severity: "error",
            message: `Unknown ${key} variant. Expected ${branches.map(branch => String(branch.value)).join(" or ")}.`,
            path,
        });
    }

    const variants = [
        ...(includeUnset ? [{
            label: effectiveDefault?.label ? `default (${effectiveDefault.label})` : "unset",
            value: "unset",
            description: effectiveDefault?.description ?? "Remove this optional configuration.",
        }] : []),
        ...branches.map(branch => ({
            label: String(branch.value),
            value: branch.value,
            description: branch.description,
        })),
    ];

    return finalizeNode({
        id: `edit:${path.join(".")}`,
        path,
        label: `${key}: < ${unset ? unsetLabel : selectedValue ?? "required"} >`,
        value: unset ? "unset" : selectedValue,
        valueKind: "union",
        presence,
        expert,
        description,
        effectiveDefault,
        status: missing ? "required" : unknown ? "error" : "ok",
        diagnostics,
        variants,
        children: selected
            ? schemaObjectChildren(path, selected.optionSchema, value, new Set([discriminator]))
            : isPlainObject(value) ? objectChildrenFromValue(path, value) : [],
    });
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
    const visit = (node: EditNode): EditNode[] => {
        if (node.valueKind === "command") {
            return [];
        }
        const childAncestors = (node.children ?? []).flatMap(child => visit(child));
        if (pathStartsWith(path, node.path) || childAncestors.length) {
            return [node, ...childAncestors];
        }
        return [];
    };
    return nodes.flatMap(node => visit(node));
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
        const alreadyRepresentedRequired = severity === "required" && Boolean(target.statusCounts?.required);
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
    inputHint?: EditInputHint,
    valueType?: EditNode["valueType"],
    expert = false,
    presence: EditNode["presence"] = required ? "required" : "optional",
    externalRef?: ExternalRefHint
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
        valueType: valueType ?? (typeof value === "number" ? "number" : "string"),
        valueKind: typeof value === "boolean" ? "boolean" : "scalar",
        presence,
        expert,
        description,
        required,
        inputHint,
        externalRef,
        validation,
        status: missing ? "required" : patternMismatch ? "error" : "ok",
        diagnostics,
    });
}

function booleanNode(
    path: string[],
    key: string,
    value: unknown,
    description: string,
    expert = false,
    presence: EditNode["presence"] = "optional"
): EditNode {
    return finalizeNode({
        id: `edit:${path.join(".")}`,
        path,
        label: `${key}: ${value === true ? "true" : "false"}`,
        value: value === true,
        valueType: "boolean",
        valueKind: "boolean",
        presence,
        expert,
        description,
        status: "ok",
    });
}

function objectRefNode(
    path: string[],
    key: string,
    value: unknown,
    description: string,
    required: boolean,
    externalRef?: ExternalRefHint,
): EditNode {
    const refValue = isPlainObject(value) ? value : {};
    const name = typeof refValue.name === "string" ? refValue.name : "";
    const kind = typeof refValue.kind === "string" ? refValue.kind : "";
    const group = typeof refValue.group === "string" ? refValue.group : "";
    const missing = required && !name;
    const suffix = name
        ? `${name}${kind ? ` (${kind}${group ? `, ${group}` : ""})` : ""}`
        : required ? "<required>" : "<unset>";
    const diagnostics: EditDiagnostic[] = missing
        ? [{severity: "required", message: `${key} is required.`, path}]
        : [];
    return finalizeNode({
        id: `edit:${path.join(".")}`,
        path,
        label: `${key}: ${suffix}`,
        value: refValue,
        valueKind: "object",
        presence: required ? "required" : "optional",
        description,
        required,
        externalRef,
        status: missing ? "required" : "ok",
        diagnostics,
        children: objectChildrenFromValue(path, refValue),
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
                BASIC_SECRET_NAME_HINT,
                "string",
                false,
                "required",
                BASIC_SECRET_EXTERNAL_REF
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
            scalarNode(
                [...path, "mtls", "clientSecretName"],
                "clientSecretName",
                authConfig?.mtls?.clientSecretName,
                "Name of a Kubernetes TLS Secret containing the client certificate and private key for mutual TLS authentication.",
                true,
                MTLS_CLIENT_SECRET_NAME_HINT,
                "string",
                false,
                "required"
            ),
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
        presence: "optional",
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

function proxyClientAuthMode(config: unknown): "disabled" | "enabled" | "unknown" {
    if (config === undefined || config === null) {
        return "disabled";
    }
    if (isPlainObject(config)) {
        return "enabled";
    }
    return "unknown";
}

function proxyClientAuthNode(path: string[], config: unknown): EditNode {
    const mode = proxyClientAuthMode(config);
    const clientAuth = isPlainObject(config) ? config as Record<string, unknown> : {};
    const diagnostics: EditDiagnostic[] = mode === "unknown"
        ? [{severity: "error", message: "Unknown clientAuth value. Expected an object or omission.", path}]
        : [];
    const description = descriptionOf(PROXY_TLS_CLIENT_AUTH_CONFIG)
        ?? "Optional mutual TLS client-authentication configuration for the capture proxy listener.";
    return finalizeNode({
        id: `edit:${path.join(".")}`,
        path,
        label: `clientAuth: < ${mode} >`,
        value: mode,
        valueKind: "union",
        presence: "optional",
        description,
        descriptionShort: description,
        status: mode === "unknown" ? "error" : "ok",
        diagnostics,
        variants: [
            {
                label: "disabled",
                value: "disabled",
                description: "Do not require client certificates when console commands connect to the proxy.",
            },
            {
                label: "enabled",
                value: "enabled",
                description: "Require client certificates signed by the configured trusted client CA.",
            },
        ],
        children: mode === "enabled"
            ? [
                genericDisplayNode(
                    [...path, "trustedClientCaFile"],
                    "trustedClientCaFile",
                    clientAuth.trustedClientCaFile,
                    "optional",
                    false,
                    "PEM trusted CA certificate file used to verify client certificates accepted by the capture proxy.",
                ),
                scalarNode(
                    [...path, "trustedClientCaPem"],
                    "trustedClientCaPem",
                    clientAuth.trustedClientCaPem ?? "",
                    "Inline PEM trusted CA certificate used to verify client certificates accepted by the capture proxy.",
                    false,
                    undefined,
                    "string",
                ),
                scalarNode(
                    [...path, "consoleClientSecretName"],
                    "consoleClientSecretName",
                    clientAuth.consoleClientSecretName,
                    "Name of a Kubernetes TLS Secret containing the client certificate and private key that migration-console commands use when connecting to this mTLS-enabled proxy.",
                    false,
                    PROXY_CLIENT_AUTH_CONSOLE_SECRET_HINT,
                    "string",
                    false,
                    "optional",
                    PROXY_CLIENT_AUTH_CONSOLE_SECRET_EXTERNAL_REF,
                ),
                booleanNode(
                    [...path, "required"],
                    "required",
                    clientAuth.required ?? true,
                    "When true, clients must present a certificate signed by the configured trusted client CA. Defaults to true.",
                ),
            ]
            : mode === "unknown" ? objectChildrenFromValue(path, config) : [],
    });
}

function proxyTlsChildren(path: string[], mode: ReturnType<typeof proxyTlsMode>, config: any): EditNode[] {
    if (mode === "existingSecret") {
        return [
            scalarNode(
                [...path, "secretName"],
                "secretName",
                config?.secretName,
                "Name of an existing Kubernetes TLS secret containing 'tls.crt' and 'tls.key' entries. The secret is mounted into the proxy pod at /etc/proxy-tls/.",
                true,
                PROXY_TLS_SECRET_NAME_HINT,
                "string",
                false,
                "required",
                PROXY_TLS_SECRET_EXTERNAL_REF
            ),
            proxyClientAuthNode([...path, "clientAuth"], config?.clientAuth),
        ];
    }
    if (mode === "certManager") {
        return [
            objectRefNode(
                [...path, "issuerRef"],
                "issuerRef",
                config?.issuerRef,
                "Reference to a cert-manager issuer that will sign TLS certificates for the proxy.",
                true,
                PROXY_TLS_ISSUER_EXTERNAL_REF
            ),
            genericDisplayNode([...path, "dnsNames"], "dnsNames", config?.dnsNames, "required", false, "DNS Subject Alternative Names for the certificate. Must include the proxy's Kubernetes service DNS name."),
            scalarNode([...path, "commonName"], "commonName", config?.commonName ?? "", "Optional common name (CN) for the TLS certificate subject."),
            scalarNode([...path, "duration"], "duration", config?.duration ?? "2160h", "Requested certificate validity duration in Go duration format (e.g. '2160h' = 90 days)."),
            scalarNode([...path, "renewBefore"], "renewBefore", config?.renewBefore ?? "360h", "How long before certificate expiry to trigger renewal (e.g. '360h' = 15 days)."),
            proxyClientAuthNode([...path, "clientAuth"], config?.clientAuth),
        ];
    }
    if (mode === "unknown") {
        return objectChildrenFromValue(path, config);
    }
    return [];
}

function proxyTlsNode(
    path: string[],
    value: unknown,
    description: string,
    expert: boolean,
    presence: EditNode["presence"],
): EditNode {
    const mode = proxyTlsMode(value);
    const diagnostics: EditDiagnostic[] = mode === "unknown"
        ? [{severity: "error", message: "Unknown TLS mode. Expected certManager, existingSecret, plaintext, or omitted.", path}]
        : [];
    return finalizeNode({
        id: `edit:${path.join(".")}`,
        path,
        label: `tls: < ${mode === "unset" ? "default" : mode} >`,
        value: mode,
        valueKind: "union",
        presence,
        expert,
        description,
        status: mode === "unknown" ? "error" : "ok",
        diagnostics,
        variants: [
            {
                label: "default",
                value: "unset",
                description: "Use the workflow's secure-by-default proxy TLS behavior.",
            },
            {
                label: "existingSecret",
                value: "existingSecret",
                description: descriptionOf(PROXY_TLS_EXISTING_SECRET_SCHEMA),
            },
            {
                label: "certManager",
                value: "certManager",
                description: descriptionOf(PROXY_TLS_CERT_MANAGER_SCHEMA),
            },
            {
                label: "plaintext",
                value: "plaintext",
                description: descriptionOf(PROXY_TLS_PLAINTEXT_SCHEMA),
            },
        ],
        children: proxyTlsChildren(path, mode, value),
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
        presence: "optional",
        description: "Snapshot repository and snapshot configurations for this source cluster. Required if any snapshot-based migrations reference this source.",
        status: "ok",
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
        label: `kafka: ${name}`,
        valueKind: "object",
        description: KAFKA_CLUSTER_DESCRIPTION,
        status: "ok",
        children: [modeNode, ...branchChildren],
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
        label: "Kafka Clients",
        valueKind: "record",
        description: KAFKA_CLUSTERS_DESCRIPTION,
        inputHint: KAFKA_RECORD_HINT,
        status: "ok",
        children,
    });
}

function objectChildrenFromValue(path: string[], value: unknown): EditNode[] {
    if (!isPlainObject(value)) {
        return [];
    }
    return Object.entries(value)
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([key, childValue]) => genericDisplayNode([...path, key], key, childValue, "optional", false, ""));
}

function genericDisplayNode(
    path: string[],
    key: string,
    value: unknown,
    presence: EditNode["presence"],
    expert: boolean,
    description: string,
): EditNode {
    if (typeof value === "boolean") {
        return booleanNode(path, key, value, description, expert, presence);
    }
    if (typeof value === "number") {
        return scalarNode(path, key, value, description, false, undefined, "number", expert, presence);
    }
    if (typeof value === "string" || value === undefined || value === null) {
        return scalarNode(path, key, value ?? "", description, false, undefined, "string", expert, presence);
    }
    if (Array.isArray(value)) {
        return finalizeNode({
            id: `edit:${path.join(".")}`,
            path,
            label: `${key}: ${value.length ? `${value.length} item${value.length === 1 ? "" : "s"}` : "[]"}`,
            value,
            valueKind: "array",
            presence,
            expert,
            description,
            status: "ok",
        });
    }
    return finalizeNode({
        id: `edit:${path.join(".")}`,
        path,
        label: key,
        value,
        valueKind: "object",
        presence,
        expert,
        description,
        status: "ok",
        children: objectChildrenFromValue(path, value),
    });
}

function schemaFieldNode(rootPath: string[], key: string, schema: any, config: Record<string, unknown>): EditNode {
    const path = [...rootPath, key];
    const description = schemaDescription(schema);
    const required = isRequiredSchema(schema);
    const presence: EditNode["presence"] = required ? "required" : "optional";
    const expert = isExpertDescription(description);
    const hasValue = Object.hasOwn(config, key);
    const value = hasValue ? config[key] : defaultValueForSchema(schema);
    const inputHint = uiHintOf(schema);
    const externalRef = externalRefOf(schema);
    const scalarType = schemaScalarType(schema);

    if (isGenericRecordSchema(schema)) {
        const jsonSchema = jsonSchemaForConfigPath(path);
        if (jsonSchema && (
            Object.keys(jsonSchemaProperties(jsonSchema)).length > 0
            || jsonSchemaBranches(jsonSchema).length > 0
            || jsonSchemaEnumValues(jsonSchema).length > 0
        )) {
            return jsonSchemaFieldNode(rootPath, key, jsonSchema, config, required);
        }
    }
    if (key === "tls" && schema === unwrapSchema(USER_PROXY_OPTIONS).shape?.tls) {
        return proxyTlsNode(path, value, description, expert, presence);
    }
    const unionNode = discriminatedUnionNode(path, key, schema, value, hasValue, description, required, expert, presence);
    if (unionNode) {
        return unionNode;
    }
    if (scalarType === "boolean") {
        return booleanNode(path, key, value === true, description, expert, presence);
    }
    if (scalarType === "number") {
        return scalarNode(path, key, value, description, required, inputHint, "number", expert, presence, externalRef);
    }
    if (scalarType === "string") {
        return scalarNode(path, key, value ?? "", description, required, inputHint, "string", expert, presence, externalRef);
    }
    if (value === undefined || value === null) {
        const containerKind = schemaContainerKind(schema);
        if (containerKind) {
            return finalizeNode({
                id: `edit:${path.join(".")}`,
                path,
                label: `${key}: <unset>`,
                value,
                valueKind: containerKind,
                presence,
                expert,
                description,
                status: "ok",
            });
        }
        return scalarNode(path, key, "", description, required, inputHint, "string", expert, presence, externalRef);
    }
    return genericDisplayNode(path, key, value, presence, expert, description);
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
        .map(key => schemaFieldNode(rootPath, key, schemaShape[key], proxyConfig));
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
        description: "Process-level and deployment-level configuration options for the capture proxy.",
        status: "ok",
        children: [...children, ...extraChildren],
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
            captureProxyConfigNode([...rootPath, "proxyConfig"], value?.proxyConfig),
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
                label: "Buffer",
                valueKind: "record",
                description: "Captured traffic buffers and pre-recorded traffic archives loaded into Kafka for replay.",
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
        label: "Backfill",
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
        label: kind === "source" ? "Sources" : "Targets",
        valueKind: "record",
        description: kind === "source" ? SOURCE_CLUSTERS_DESCRIPTION : TARGET_CLUSTERS_DESCRIPTION,
        inputHint: kind === "source" ? SOURCE_RECORD_HINT : TARGET_RECORD_HINT,
        status: "ok",
        children,
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
        workflowConfigurationNode(config),
        snapshotMigrationSectionNode(config, ctx),
        trafficGroupNode(config?.traffic, ctx),
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

function childSchemaAtPath(schema: any, path: string[]): any | undefined {
    if (!path.length) {
        return schema;
    }

    const [part, ...rest] = path;
    const shape = schemaShape(schema);
    if (shape?.[part]) {
        return childSchemaAtPath(shape[part], rest);
    }

    const keyedBranch = singleKeyUnionBranches(schema).find(branch => branch.value === part);
    if (keyedBranch) {
        return childSchemaAtPath(keyedBranch.fieldSchema, rest);
    }

    const discriminator = discriminatorForSchema(schema);
    if (discriminator) {
        const branch = schemaOptions(schema).find(optionSchema => Boolean(schemaShape(optionSchema)?.[part]));
        const branchShape = branch ? schemaShape(branch) : undefined;
        if (branchShape?.[part]) {
            return childSchemaAtPath(branchShape[part], rest);
        }
    }

    const unwrapped = unwrapSchema(schema);
    const elementSchema = unwrapped?.element ?? unwrapped?._def?.element;
    if (elementSchema && isArrayIndex(part)) {
        return childSchemaAtPath(elementSchema, rest);
    }

    return undefined;
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

function singleKeyUnionValueForVariant(schema: any, existing: any, variant: unknown): unknown {
    const branch = singleKeyUnionBranches(schema).find(item => item.value === variant);
    if (!branch) {
        throw new Error(`Unknown variant: ${String(variant)}`);
    }
    return {
        [branch.value]: isPlainObject(existing?.[branch.value]) ? existing[branch.value] : {},
    };
}

function kafkaConfigForVariant(existing: any, variant: unknown): unknown {
    return singleKeyUnionValueForVariant(KAFKA_CLUSTER_CONFIG, existing, variant);
}

function discriminatedUnionValueForVariant(schema: any, existing: any, variant: unknown): unknown {
    if (variant === "unset" || variant === null || variant === undefined || variant === "") {
        return undefined;
    }

    const discriminator = discriminatorForSchema(schema);
    if (!discriminator) {
        throw new Error("Schema is not a discriminated union");
    }
    const branch = discriminatedUnionBranches(schema, discriminator).find(item => item.value === variant);
    if (!branch) {
        throw new Error(`Unknown ${discriminator} variant: ${String(variant)}`);
    }

    const branchShape = schemaShape(branch.optionSchema) ?? {};
    const next: Record<string, unknown> = {[discriminator]: variant};
    for (const [key, fieldSchema] of Object.entries(branchShape)) {
        if (key === discriminator) {
            continue;
        }
        if (isPlainObject(existing) && Object.hasOwn(existing, key)) {
            next[key] = existing[key];
            continue;
        }
        const defaultValue = defaultValueForSchema(fieldSchema);
        if (defaultValue !== undefined) {
            next[key] = defaultValue;
        }
    }
    return next;
}

function jsonDiscriminatedUnionValueForVariant(schema: JsonSchema, existing: any, variant: unknown): unknown {
    if (variant === "unset" || variant === null || variant === undefined || variant === "") {
        return undefined;
    }

    const discriminator = jsonSchemaDiscriminator(schema);
    if (!discriminator) {
        throw new Error("JSON schema is not a discriminated union");
    }
    const branch = jsonSchemaBranches(schema).find(item =>
        jsonSchemaEnumValues(jsonSchemaProperty(item, discriminator))[0] === variant
    );
    if (!branch) {
        throw new Error(`Unknown ${discriminator} variant: ${String(variant)}`);
    }

    const next: Record<string, unknown> = {[discriminator]: variant};
    for (const [key, fieldSchema] of Object.entries(jsonSchemaProperties(branch))) {
        if (key === discriminator) {
            continue;
        }
        if (isPlainObject(existing) && Object.hasOwn(existing, key)) {
            next[key] = existing[key];
            continue;
        }
        const defaultValue = resolveJsonSchemaRef(fieldSchema)?.default;
        if (defaultValue !== undefined) {
            next[key] = defaultValue;
        }
    }
    return next;
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
        return isPlainObject(existing) ? existing : {required: true};
    }
    throw new Error(`Unknown proxy clientAuth mode: ${String(variant)}`);
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
    if (jsonSchema && jsonSchemaEnumValues(jsonSchema).length > 0 && value === "unset") {
        delete parent[key];
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

function unsetAtPath(config: any, path: string[]): void {
    const resolved = existingParentAtPath(config, path);
    if (!resolved) {
        return;
    }
    const {parent, key} = resolved;
    if (Array.isArray(parent) && isArrayIndex(key)) {
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
        return {source: "", proxyConfig: {}};
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
