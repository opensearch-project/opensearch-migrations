import {
    EffectiveDefaultHint,
    ExternalRefHint,
    FieldMeta,
    UiHint,
    getDescription,
    loadUnifiedSchema,
    unwrapSchema as unwrapSchemaWithPipes,
} from "@opensearch-migrations/schemas";

export type EditNodeStatus = "ok" | "required" | "error" | "warning" | "changed" | "gated" | "blocked";
export interface EditDiagnostic {
    severity: Exclude<EditNodeStatus, "ok" | "changed">;
    message: string;
    path?: string[];
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
    valueDefaulted?: boolean;
    valueAuthored?: boolean;
    valueType?: "string" | "number" | "boolean";
    valueKind: "object" | "record" | "array" | "union" | "boolean" | "scalar" | "command";
    presence?: "required" | "optional";
    expert?: boolean;
    essential?: boolean;
    description?: string;
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
    validation?: {
        pattern?: string;
        message?: string;
    };
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
        editAdded?: boolean;
        autoEditAdded?: boolean;
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

export type JsonSchema = Record<string, any>;
type StatusCounts = NonNullable<EditNode["statusCounts"]>;

export interface SingleKeyUnionBranch {
    value: string;
    optionSchema: any;
    fieldSchema: any;
    description?: string;
}

export interface DiscriminatedUnionBranch {
    value: unknown;
    optionSchema: any;
    description?: string;
}

export interface ObjectUnionBranch {
    value: string;
    optionSchema: any;
    description?: string;
}

function objectUnionVariantLabel(path: string[], key: string, value: string): string {
    if (key === "entryPoint") {
        const labels: Record<string, string> = {
            javascript: "inline JavaScript",
            javascriptFile: "external JavaScript file",
            python: "inline Python",
            pythonFile: "external Python file",
        };
        return labels[value] ?? value;
    }
    if (value === "image" && path.some(part => part.endsWith("File") || part === "fromFile")) {
        return "mountable image";
    }
    if (value === "configMap" && path.some(part => part.endsWith("File") || part === "fromFile")) {
        return "ConfigMap key";
    }
    return value;
}

const STATUS_PRIORITY: EditNodeStatus[] = ["blocked", "error", "required", "gated", "warning", "changed", "ok"];
const STATUS_COUNT_KEYS: Partial<Record<EditNodeStatus, keyof StatusCounts>> = {
    required: "required",
    error: "errors",
    warning: "warnings",
    changed: "changed",
    gated: "gated",
    blocked: "blocked",
};
const SCHEMA_SCALAR_TYPES: Record<string, EditNode["valueType"]> = {
    ZodNumber: "number",
    ZodBoolean: "boolean",
    ZodString: "string",
    ZodLiteral: "string",
};
const SCHEMA_OBJECT_TYPES = new Set(["ZodObject", "ZodRecord", "ZodUnion", "ZodDiscriminatedUnion"]);

export function descriptionOf(schema: any): string | undefined {
    return getDescription(schema) ?? schema?.description;
}

export function unwrapSchema(schema: any): any {
    return schema ? unwrapSchemaWithPipes(schema) : schema;
}

function schemaMetaValue<K extends keyof FieldMeta>(schema: any, key: K): FieldMeta[K] | undefined {
    const direct = schema?.meta?.() as FieldMeta | undefined;
    const unwrapped = unwrapSchema(schema);
    const inner = unwrapped === schema ? undefined : unwrapped?.meta?.() as FieldMeta | undefined;
    return direct?.[key] ?? inner?.[key];
}

export function uiHintOf(schema: any): EditInputHint | undefined {
    const hint = schemaMetaValue(schema, "uiHint");
    return hint ? {...hint} as EditInputHint : undefined;
}

export function externalRefOf(schema: any): ExternalRefHint | undefined {
    const hint = schemaMetaValue(schema, "externalRef");
    return hint ? structuredClone(hint) : undefined;
}

export function effectiveDefaultOf(schema: any): EffectiveDefaultHint | undefined {
    const hint = schemaMetaValue(schema, "effectiveDefault");
    return hint ? structuredClone(hint) : undefined;
}

export function essentialOf(schema: any): boolean {
    return schemaMetaValue(schema, "essential") === true;
}

export function expertOf(schema: any): boolean {
    return schemaMetaValue(schema, "expert") === true;
}

export function uiHintAt(schema: any, path: string[]): EditInputHint | undefined {
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

export function discriminatedUnionOption(schema: any, discriminator: string, value: unknown): any | undefined {
    const unwrapped = unwrapSchema(schema);
    const options = Array.isArray(unwrapped?.options) ? unwrapped.options : [];
    return options.find((option: any) => {
        const shape = unwrapSchema(option)?.shape ?? {};
        return literalValues(shape[discriminator]).has(value);
    });
}

export function literalValues(schema: any): Set<unknown> {
    const literal = unwrapSchema(schema);
    if (literal?.values instanceof Set) {
        return literal.values;
    }
    if (Object.hasOwn(literal ?? {}, "value")) {
        return new Set([literal.value]);
    }
    return new Set();
}

export function zodEnumValues(schema: any): unknown[] {
    const unwrapped = unwrapSchema(schema);
    if (String(unwrapped?.constructor?.name ?? "") !== "ZodEnum") {
        return [];
    }
    if (Array.isArray(unwrapped.options)) {
        return unwrapped.options;
    }
    const values = unwrapped._def?.values ?? unwrapped.def?.values;
    if (Array.isArray(values)) {
        return values;
    }
    const entries = unwrapped._def?.entries ?? unwrapped.def?.entries;
    if (isPlainObject(entries)) {
        return Object.values(entries);
    }
    return [];
}

export function validationFromHint(inputHint?: EditInputHint): EditNode["validation"] | undefined {
    return inputHint?.kind === "text" && inputHint.pattern
        ? {pattern: inputHint.pattern, message: inputHint.message}
        : undefined;
}

export function isRequiredSchema(schema: any): boolean {
    return schema?.safeParse?.(undefined)?.success === false;
}

export function defaultValueForSchema(schema: any): unknown {
    const parsed = schema?.safeParse?.(undefined);
    if (parsed?.success && parsed.data !== undefined) {
        return parsed.data;
    }
    return wrappedDefaultValueForSchema(schema);
}

function wrappedDefaultValueForSchema(schema: any, seen = new Set<any>()): unknown {
    if (!schema || seen.has(schema)) {
        return undefined;
    }
    seen.add(schema);

    const constructorName = String(schema?.constructor?.name ?? "");
    if (constructorName === "ZodDefault") {
        const parsed = schema.safeParse?.(undefined);
        if (parsed?.success) {
            return parsed.data;
        }
    }

    const inner = schema.unwrap?.() ?? schema._def?.innerType ?? schema.def?.innerType;
    if (inner) {
        return wrappedDefaultValueForSchema(inner, seen);
    }

    if (constructorName === "ZodPipe") {
        const def = schema._def ?? schema.def;
        return wrappedDefaultValueForSchema(def?.in?.constructor?.name === "ZodTransform" ? def.out : def?.in, seen);
    }

    return undefined;
}

export function schemaDescription(schema: any): string {
    return descriptionOf(schema) ?? descriptionOf(unwrapSchema(schema)) ?? "";
}

export function isExpertDescription(description: string): boolean {
    return /^\s*\[Expert\]/i.test(description) || /^\s*Expert\b/i.test(description);
}

export function isPlainObject(value: unknown): value is Record<string, unknown> {
    return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function schemaConstructorName(schema: any): string {
    return String(unwrapSchema(schema)?.constructor?.name ?? "");
}

function schemaScalarType(schema: any): EditNode["valueType"] | undefined {
    return SCHEMA_SCALAR_TYPES[schemaConstructorName(schema)];
}

function schemaContainerKind(schema: any): "array" | "object" | undefined {
    const name = schemaConstructorName(schema);
    if (name === "ZodArray") {
        return "array";
    }
    return SCHEMA_OBJECT_TYPES.has(name) ? "object" : undefined;
}

function zodRecordValueSchema(schema: any): any | undefined {
    const unwrapped = unwrapSchema(schema);
    return unwrapped?.valueType ?? unwrapped?._def?.valueType;
}

export function schemaArrayElement(schema: any): any | undefined {
    const unwrapped = unwrapSchema(schema);
    if (schemaConstructorName(unwrapped) !== "ZodArray") {
        return undefined;
    }
    return unwrapped?.element ?? unwrapped?._def?.element ?? unwrapped?._def?.type;
}

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

export function resolveJsonSchemaRef(schema: JsonSchema | undefined, root: JsonSchema | undefined = unifiedSchema()): JsonSchema | undefined {
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

export function jsonSchemaBranches(schema: JsonSchema | undefined): JsonSchema[] {
    const resolved = resolveJsonSchemaRef(schema);
    const branches = resolved?.oneOf ?? resolved?.anyOf;
    return Array.isArray(branches)
        ? branches.filter(isJsonSchemaObject).map(branch => resolveJsonSchemaRef(branch) ?? branch)
        : [];
}

export function jsonSchemaProperties(schema: JsonSchema | undefined): Record<string, JsonSchema> {
    const resolved = resolveJsonSchemaRef(schema);
    return isJsonSchemaObject(resolved?.properties) ? resolved.properties : {};
}

export function jsonSchemaRequired(schema: JsonSchema | undefined): Set<string> {
    const required = resolveJsonSchemaRef(schema)?.required;
    return new Set(Array.isArray(required) ? required.map(String) : []);
}

export function jsonSchemaType(schema: JsonSchema | undefined): string | undefined {
    const type = resolveJsonSchemaRef(schema)?.type;
    return Array.isArray(type) ? type.find(item => item !== "null") : type;
}

export function jsonSchemaProperty(schema: JsonSchema | undefined, key: string): JsonSchema | undefined {
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

export function jsonSchemaForConfigPath(path: string[]): JsonSchema | undefined {
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

export function jsonSchemaEnumValues(schema: JsonSchema | undefined): unknown[] {
    const resolved = resolveJsonSchemaRef(schema);
    if (Object.hasOwn(resolved ?? {}, "const")) {
        return [resolved!.const];
    }
    return Array.isArray(resolved?.enum) ? resolved.enum : [];
}

export function defaultJsonValueForSchema(schema: JsonSchema | undefined): unknown {
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

function jsonSchemaAdditionalPropertiesSchema(schema: JsonSchema | undefined): JsonSchema | undefined {
    const additional = resolveJsonSchemaRef(schema)?.additionalProperties;
    return isJsonSchemaObject(additional) ? resolveJsonSchemaRef(additional) : undefined;
}

function jsonSchemaInputHint(schema: JsonSchema | undefined): EditInputHint | undefined {
    const resolved = resolveJsonSchemaRef(schema);
    const schemaHint = jsonSchemaUiHint(resolved);
    const pattern = typeof resolved?.pattern === "string" ? resolved.pattern : undefined;
    if (schemaHint?.kind === "javaRegex") {
        return schemaHint;
    }
    if (schemaHint?.kind === "text") {
        return {
            ...schemaHint,
            pattern: schemaHint.pattern ?? pattern,
        };
    }
    return pattern ? {
        kind: "text",
        pattern,
        message: "Value does not match the expected format.",
    } : undefined;
}

function jsonSchemaUiHint(schema: JsonSchema | undefined): EditInputHint | undefined {
    const hint = resolveJsonSchemaRef(schema)?.["x-ui-hint"];
    return isJsonSchemaObject(hint) && typeof hint.kind === "string"
        ? hint as EditInputHint
        : undefined;
}

function jsonSchemaExternalRef(schema: JsonSchema | undefined): ExternalRefHint | undefined {
    const ref = resolveJsonSchemaRef(schema)?.["x-external-ref"];
    return isJsonSchemaObject(ref) && typeof ref.kind === "string"
        ? structuredClone(ref) as ExternalRefHint
        : undefined;
}

function jsonSchemaEssential(schema: JsonSchema | undefined): boolean {
    return resolveJsonSchemaRef(schema)?.["x-essential"] === true;
}

function arrayAddLabel(inputHint: EditInputHint | undefined): string {
    return inputHint?.kind === "array" && inputHint.addLabel
        ? inputHint.addLabel
        : "item";
}

function arrayDescriptionForAdd(addLabel: string): string {
    return `Create a new ${addLabel} in pending workflow YAML.`;
}

function jsonSchemaMinItems(schema: JsonSchema | undefined): number | undefined {
    const minItems = resolveJsonSchemaRef(schema)?.minItems;
    return typeof minItems === "number" && Number.isFinite(minItems) && minItems > 0
        ? minItems
        : undefined;
}

export function jsonSchemaDiscriminator(schema: JsonSchema | undefined): string | undefined {
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

export function jsonSchemaObjectUnionBranches(schema: JsonSchema | undefined): ObjectUnionBranch[] {
    const branches = jsonSchemaBranches(schema);
    if (!branches.length) {
        return [];
    }
    const requiredKeysByBranch = branches.map(branch => [...jsonSchemaRequired(branch)]);
    const sharedRequiredKeys = requiredKeysByBranch.reduce<Set<string>>((shared, keys, index) => {
        const keySet = new Set(keys);
        if (index === 0) {
            return keySet;
        }
        return new Set([...shared].filter(key => keySet.has(key)));
    }, new Set());
    const objectBranches = branches.map((branch, index) => {
        if (!Object.keys(jsonSchemaProperties(branch)).length) {
            return undefined;
        }
        const selectorKeys = requiredKeysByBranch[index].filter(key => !sharedRequiredKeys.has(key));
        if (selectorKeys.length !== 1) {
            return undefined;
        }
        return {
            value: selectorKeys[0],
            optionSchema: branch,
            description: jsonSchemaDescription(branch),
        };
    });
    const values = objectBranches.map(branch => branch?.value);
    return objectBranches.every(Boolean) && new Set(values).size === values.length
        ? objectBranches as ObjectUnionBranch[]
        : [];
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
            ? jsonSchemaObjectChildren(path, selectedBranch, value, value, new Set([discriminator]))
            : isPlainObject(value) ? objectChildrenFromValue(path, value) : [],
    });
}

function jsonSchemaObjectUnionNode(
    path: string[],
    key: string,
    schema: JsonSchema,
    value: unknown,
    hasValue: boolean,
    required: boolean,
    expert: boolean,
    presence: EditNode["presence"],
): EditNode | undefined {
    const branches = jsonSchemaObjectUnionBranches(schema);
    if (!branches.length) {
        return undefined;
    }

    const selected = isPlainObject(value)
        ? branches.find(branch => Object.hasOwn(value, branch.value))
        : undefined;
    const selectedLabel = selected
        ? objectUnionVariantLabel(path, key, selected.value)
        : undefined;
    const unset = !required && !hasValue && value === undefined;
    const hasObjectValue = value !== undefined && value !== null;
    const missing = required && !selected;
    const unknown = hasObjectValue && !selected;
    const diagnostics: EditDiagnostic[] = [];
    if (missing) {
        diagnostics.push({severity: "required", message: `${key} is required.`, path});
    } else if (unknown) {
        diagnostics.push({
            severity: "error",
            message: `Unknown ${key} variant. Expected ${branches.map(branch => branch.value).join(" or ")}.`,
            path,
        });
    }

    return finalizeNode({
        id: `edit:${path.join(".")}`,
        path,
        label: `${key}: < ${unset ? "unset" : selectedLabel ?? (required ? "required" : "unknown")} >`,
        value: unset ? "unset" : selected?.value,
        valueKind: "union",
        presence,
        expert,
        description: jsonSchemaDescription(schema),
        status: missing ? "required" : unknown ? "error" : "ok",
        diagnostics,
        variants: [
            ...(!required ? [{label: "unset", value: "unset", description: "Remove this optional configuration."}] : []),
            ...branches.map(branch => ({
                label: objectUnionVariantLabel(path, key, branch.value),
                value: branch.value,
                description: branch.description,
            })),
        ],
        children: selected
            ? jsonSchemaObjectChildren(path, selected.optionSchema, value, value)
            : isPlainObject(value) ? objectChildrenFromValue(path, value) : [],
    });
}

function jsonSchemaMixedObjectBranch(schema: JsonSchema): JsonSchema | undefined {
    return jsonSchemaBranches(schema).find(branch =>
        jsonSchemaType(branch) === "object" || Object.keys(jsonSchemaProperties(branch)).length > 0
    );
}

function jsonSchemaMixedObjectNode(
    path: string[],
    key: string,
    schema: JsonSchema,
    value: unknown,
    required: boolean,
    expert: boolean,
    presence: EditNode["presence"],
    authoredValue: unknown,
): EditNode | undefined {
    if (value !== undefined && value !== null && !isPlainObject(value)) {
        return undefined;
    }
    const objectBranch = jsonSchemaMixedObjectBranch(schema);
    if (!objectBranch) {
        return undefined;
    }
    const missing = required && (value === undefined || value === null);
    return finalizeNode({
        id: `edit:${path.join(".")}`,
        path,
        label: `${key}: ${value === undefined || value === null ? (required ? "<required>" : "<unset>") : Object.keys(value).length ? "configured" : "{}"}`,
        value,
        valueKind: "object",
        presence,
        expert,
        description: jsonSchemaDescription(schema),
        required,
        status: missing ? "required" : "ok",
        diagnostics: missing ? [{severity: "required", message: `${key} is required.`, path}] : [],
        children: jsonSchemaObjectChildren(path, objectBranch, value, authoredValue),
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

function zodEnumNode(
    path: string[],
    key: string,
    schema: any,
    value: unknown,
    required: boolean,
    expert: boolean,
    presence: EditNode["presence"],
    defaultValue: unknown,
): EditNode {
    const values = zodEnumValues(schema);
    const unset = value === undefined || value === null || value === "";
    const defaultLabel = defaultValue === undefined ? undefined : String(defaultValue);
    const unsetLabel = defaultLabel ? `default (${defaultLabel})` : "unset";
    const displayValue = unset
        ? (required ? "required" : defaultLabel ? `default: ${defaultLabel}` : "unset")
        : String(value);
    return finalizeNode({
        id: `edit:${path.join(".")}`,
        path,
        label: `${key}: < ${displayValue} >`,
        value: unset && !required ? "unset" : value,
        valueKind: "union",
        presence,
        expert,
        description: schemaDescription(schema),
        status: required && unset ? "required" : "ok",
        diagnostics: required && unset ? [{
            severity: "required",
            message: `${key} is required.`,
            path,
        }] : [],
        variants: [
            ...(!required ? [{
                label: unsetLabel,
                value: "unset",
                description: defaultLabel
                    ? "Remove this value and use the schema default."
                    : "Remove this optional value.",
            }] : []),
            ...values.map(option => ({label: String(option), value: option})),
        ],
    });
}

function jsonSchemaObjectChildren(
    rootPath: string[],
    schema: JsonSchema,
    value: unknown,
    authoredValue: unknown,
    excludedKeys: Set<string> = new Set(),
): EditNode[] {
    const requiredKeys = jsonSchemaRequired(schema);
    const objectValue = isPlainObject(value) ? value : {};
    const authoredObject = isPlainObject(authoredValue) ? authoredValue : {};
    return Object.entries(jsonSchemaProperties(schema))
        .filter(([key]) => !excludedKeys.has(key))
        .map(([key, childSchema]) => jsonSchemaFieldNode(
            rootPath,
            key,
            childSchema,
            objectValue,
            requiredKeys.has(key),
            authoredObject,
        ));
}

function jsonSchemaArrayChildren(
    rootPath: string[],
    schema: JsonSchema,
    value: unknown,
): EditNode[] {
    const arrayValue = Array.isArray(value) ? value : [];
    const itemSchema = resolveJsonSchemaRef(schema.items);
    const addLabel = arrayAddLabel(jsonSchemaUiHint(schema));
    return [
        ...arrayValue.map((itemValue, index) => jsonSchemaArrayItemNode(rootPath, itemSchema, itemValue, index, addLabel)),
        addRow(rootPath, addLabel, arrayDescriptionForAdd(addLabel), false),
    ];
}

function recordAddLabel(inputHint: EditInputHint | undefined): string {
    return inputHint?.kind === "record" && inputHint.addLabel
        ? inputHint.addLabel
        : "item";
}

function jsonSchemaRecordChildren(
    rootPath: string[],
    schema: JsonSchema,
    value: unknown,
): EditNode[] {
    const recordValue = isPlainObject(value) ? value : {};
    const itemSchema = jsonSchemaAdditionalPropertiesSchema(schema);
    const recordHint = jsonSchemaUiHint(schema);
    const addLabel = recordAddLabel(recordHint);
    const children = Object.entries(recordValue)
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([recordKey, recordItemValue]) => {
            const node = itemSchema
                ? jsonSchemaFieldNode(rootPath, recordKey, itemSchema, {[recordKey]: recordItemValue}, true)
                : genericDisplayNode([...rootPath, recordKey], recordKey, recordItemValue, "required", false, "");
            node.collapsed = false;
            return node;
        });
    children.push(addRow(
        rootPath,
        addLabel,
        arrayDescriptionForAdd(addLabel),
        true,
        recordKeyHint(recordHint),
        false,
        true,
    ));
    return children;
}

function jsonSchemaArrayItemNode(
    rootPath: string[],
    schema: JsonSchema | undefined,
    value: unknown,
    index: number,
    itemLabel = "item",
): EditNode {
    const key = String(index);
    const node = schema
        ? jsonSchemaFieldNode(rootPath, key, schema, {[key]: value}, true)
        : genericDisplayNode([...rootPath, key], key, value, "required", false, "");
    const label = node.label;
    const valueSuffix = label.includes(":") ? label.slice(label.indexOf(":")) : "";
    node.label = `${itemLabel} ${index + 1}${valueSuffix}`;
    node.collapsed = true;
    return node;
}

function jsonSchemaFieldNode(
    rootPath: string[],
    key: string,
    schema: JsonSchema,
    config: Record<string, unknown>,
    required = false,
    authoredConfig: Record<string, unknown> = config,
): EditNode {
    const resolved = resolveJsonSchemaRef(schema) ?? schema;
    const path = [...rootPath, key];
    const {hasValue, value, valueDefaulted} = effectiveConfigValue(config, key, resolved.default, authoredConfig);
    const description = jsonSchemaDescription(resolved);
    const userRequired = jsonSchemaRequiredForUser(resolved, required, resolved.default) && !valueDefaulted;
    const presence: EditNode["presence"] = userRequired ? "required" : "optional";
    const expert = resolved["x-expert"] === true || isExpertDescription(description);
    const essential = jsonSchemaEssential(resolved);
    const externalRef = jsonSchemaExternalRef(resolved);
    const mark = (node: EditNode) => markValueState(node, valueDefaulted, hasValue, essential);
    const unionNode = jsonSchemaUnionNode(path, key, resolved, value, hasValue, userRequired, expert, presence);
    if (unionNode) {
        return mark(unionNode);
    }
    const objectVariantNode = jsonSchemaObjectUnionNode(path, key, resolved, value, hasValue, userRequired, expert, presence);
    if (objectVariantNode) {
        return mark(objectVariantNode);
    }
    const mixedObjectNode = jsonSchemaMixedObjectNode(path, key, resolved, value, userRequired, expert, presence, authoredConfig[key]);
    if (mixedObjectNode) {
        return mark(mixedObjectNode);
    }
    if (jsonSchemaEnumValues(resolved).length > 0) {
        return mark(jsonEnumNode(path, key, resolved, value, userRequired, expert, presence));
    }
    const valueType = jsonScalarValueType(resolved);
    if (valueType === "boolean") {
        return mark(booleanNode(path, key, value === true, description, expert, presence));
    }
    if (valueType === "number" || valueType === "string") {
        return mark(scalarNode(path, key, value ?? "", description, userRequired, jsonSchemaInputHint(resolved), valueType, expert, presence, externalRef));
    }
    if (externalRef?.selection?.target === "objectRef") {
        return mark(objectRefNode(path, key, value, description, userRequired, externalRef));
    }

    const containerKind = jsonSchemaType(resolved) === "array" ? "array" : "object";
    const recordItemSchema = containerKind === "object" ? jsonSchemaAdditionalPropertiesSchema(resolved) : undefined;
    const childNodes = recordItemSchema
        ? jsonSchemaRecordChildren(path, resolved, value)
        : containerKind === "object"
            ? jsonSchemaObjectChildren(path, resolved, value, authoredConfig[key])
            : jsonSchemaArrayChildren(path, resolved, value);
    const minItems = containerKind === "array" ? jsonSchemaMinItems(resolved) : undefined;
    const missingArrayItems = userRequired && minItems !== undefined && Array.isArray(value) && value.length < minItems;
    const missing = userRequired && (value === undefined || value === null || missingArrayItems);
    const diagnostics: EditDiagnostic[] = missing
        ? [{
            severity: "required",
            message: missingArrayItems
                ? `${key} requires at least ${minItems} item${minItems === 1 ? "" : "s"}.`
                : `${key} is required.`,
            path,
        }]
        : [];
    if (childNodes.length || value === undefined || value === null || isPlainObject(value) || Array.isArray(value)) {
        return mark(finalizeNode({
            id: `edit:${path.join(".")}`,
            path,
            label: `${key}: ${value === undefined || value === null ? (userRequired ? "<required>" : "<unset>") : Array.isArray(value) ? `${value.length} item${value.length === 1 ? "" : "s"}` : isPlainObject(value) ? (recordItemSchema ? `${Object.keys(value).length} item${Object.keys(value).length === 1 ? "" : "s"}` : (Object.keys(value).length ? "configured" : "{}")) : String(value)}`,
            value,
            valueKind: recordItemSchema ? "record" : containerKind,
            presence,
            expert,
            description,
            required: userRequired,
            status: missing ? "required" : "ok",
            diagnostics,
            children: childNodes,
        }));
    }

    return mark(genericDisplayNode(path, key, value, presence, expert, description));
}

function markValueState(node: EditNode, valueDefaulted: boolean, valueAuthored: boolean, essential = false): EditNode {
    if (valueDefaulted) {
        node.valueDefaulted = true;
    }
    if (valueAuthored) {
        node.valueAuthored = true;
    }
    if (essential) {
        node.essential = true;
    }
    return node;
}

function effectiveConfigValue(
    config: Record<string, unknown>,
    key: string,
    defaultValue: unknown,
    authoredConfig: Record<string, unknown> = config,
) {
    const hasValue = Object.hasOwn(authoredConfig, key);
    const hasEffectiveValue = Object.hasOwn(config, key);
    return {
        hasValue,
        value: hasEffectiveValue ? config[key] : defaultValue,
        valueDefaulted: !hasValue && (hasEffectiveValue || defaultValue !== undefined),
    };
}

function jsonSchemaRequiredForUser(schema: JsonSchema, required: boolean, defaultValue: unknown): boolean {
    if (!required) {
        return false;
    }
    const resolved = resolveJsonSchemaRef(schema) ?? schema;
    if (isJsonSchemaObject(resolved?.["x-effective-default"])) {
        return false;
    }
    return !defaultSatisfiesRequiredField(defaultValue, resolved);
}

function zodSchemaRequiredForUser(schema: any, required: boolean, defaultValue: unknown): boolean {
    if (!required) {
        return false;
    }
    if (effectiveDefaultOf(schema)) {
        return false;
    }
    return !defaultSatisfiesRequiredField(defaultValue);
}

function defaultSatisfiesRequiredField(defaultValue: unknown, schema?: JsonSchema): boolean {
    if (defaultValue === undefined || defaultValue === null || defaultValue === "") {
        return false;
    }
    if (Array.isArray(defaultValue)) {
        const minItems = jsonSchemaMinItems(schema);
        return minItems === undefined || defaultValue.length >= minItems;
    }
    return true;
}

export function schemaOptions(schema: any): any[] {
    const unwrapped = unwrapSchema(schema);
    const options = unwrapped?.options ?? unwrapped?._def?.options;
    return Array.isArray(options) ? options : [];
}

export function schemaShape(schema: any): Record<string, any> | undefined {
    const shape = unwrapSchema(schema)?.shape;
    return isPlainObject(shape) ? shape : undefined;
}

export function singleKeyUnionBranches(schema: any): SingleKeyUnionBranch[] {
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

export function singleKeyUnionBranchFor(schema: any, value: string): SingleKeyUnionBranch | undefined {
    return singleKeyUnionBranches(schema).find(branch => branch.value === value);
}

export function objectUnionBranches(schema: any): ObjectUnionBranch[] {
    const options = schemaOptions(schema);
    if (!options.length) {
        return [];
    }
    const optionShapes = options.map(optionSchema => schemaShape(optionSchema));
    if (!optionShapes.every(Boolean)) {
        return [];
    }
    const requiredKeysByBranch = optionShapes.map(shape =>
        Object.entries(shape ?? {})
            .filter(([, fieldSchema]) => isRequiredSchema(fieldSchema))
            .map(([key]) => key)
    );
    const sharedRequiredKeys = requiredKeysByBranch.reduce<Set<string>>((shared, keys, index) => {
        const keySet = new Set(keys);
        if (index === 0) {
            return keySet;
        }
        return new Set([...shared].filter(key => keySet.has(key)));
    }, new Set());
    const branches = options.map((optionSchema, index) => {
        const selectorKeys = requiredKeysByBranch[index].filter(key => !sharedRequiredKeys.has(key));
        if (selectorKeys.length !== 1) {
            return undefined;
        }
        return {
            value: selectorKeys[0],
            optionSchema,
            description: schemaDescription(optionSchema),
        };
    });
    const values = branches.map(branch => branch?.value);
    return branches.every(Boolean) && new Set(values).size === values.length
        ? branches as ObjectUnionBranch[]
        : [];
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

export function schemaObjectChildren(
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

export function schemaFieldNodeFor(
    parentSchema: any,
    rootPath: string[],
    key: string,
    value: unknown,
    referenceOptions?: EditInputHint["options"],
): EditNode {
    const fieldSchema = schemaShape(parentSchema)?.[key];
    const config = isPlainObject(value) ? value : {};
    const node = fieldSchema
        ? schemaFieldNode(rootPath, key, fieldSchema, config)
        : genericDisplayNode([...rootPath, key], key, config[key], "optional", false, "");
    if (referenceOptions && node.inputHint) {
        node.inputHint = {...node.inputHint, options: referenceOptions};
    }
    return node;
}

export function schemaNestedObjectChildren(
    parentSchema: any,
    rootPath: string[],
    key: string,
    value: any,
): EditNode[] {
    const nestedSchema = schemaShape(parentSchema)?.[key];
    return nestedSchema
        ? schemaObjectChildren([...rootPath, key], nestedSchema, value?.[key])
        : [];
}

export function schemaFieldDescription(parentSchema: any, key: string, fallback: string): string {
    return schemaDescription(schemaShape(parentSchema)?.[key]) || fallback;
}

export function singleKeyUnionMode(
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

export function optionalSingleKeyUnionNode(
    path: string[],
    key: string,
    schema: any,
    value: unknown,
    options: {
        unsetLabel: string;
        unsetValue: unknown;
        unsetDescription?: string;
        description?: string;
        unknownMessage?: string;
        presence?: EditNode["presence"];
        expert?: boolean;
    },
): EditNode {
    const branches = singleKeyUnionBranches(schema);
    const presentBranch = isPlainObject(value)
        ? branches.find(branch => Object.hasOwn(value, branch.value))
        : undefined;
    const hasObjectValue = value !== undefined && value !== null;
    const unknown = hasObjectValue && !presentBranch;
    const selectedValue = presentBranch?.value ?? options.unsetValue;
    const branchValue = presentBranch && isPlainObject(value) && isPlainObject(value[presentBranch.value])
        ? value[presentBranch.value]
        : {};
    const diagnostics: EditDiagnostic[] = unknown
        ? [{
            severity: "error",
            message: options.unknownMessage ?? `Unknown ${key} variant. Expected ${branches.map(branch => branch.value).join(" or ")} or omitted.`,
            path,
        }]
        : [];

    return finalizeNode({
        id: `edit:${path.join(".")}`,
        path,
        label: `${key}: < ${presentBranch ? presentBranch.value : options.unsetLabel} >`,
        value: selectedValue,
        valueKind: "union",
        presence: options.presence ?? "optional",
        expert: options.expert ?? false,
        description: options.description,
        status: unknown ? "error" : "ok",
        diagnostics,
        variants: [
            {
                label: options.unsetLabel,
                value: options.unsetValue,
                description: options.unsetDescription,
            },
            ...branches.map(branch => ({
                label: branch.value,
                value: branch.value,
                description: branch.description,
            })),
        ],
        children: presentBranch
            ? schemaNestedObjectChildren(presentBranch.optionSchema, path, presentBranch.value, value)
            : unknown ? objectChildrenFromValue(path, value) : [],
    });
}

export function optionalObjectToggleNode(
    path: string[],
    key: string,
    schema: any,
    value: unknown,
    options: {
        disabledLabel: string;
        disabledValue: unknown;
        disabledDescription?: string;
        enabledLabel: string;
        enabledValue: unknown;
        enabledDescription?: string;
        description?: string;
        unknownMessage?: string;
        presence?: EditNode["presence"];
        expert?: boolean;
    },
): EditNode {
    const enabled = isPlainObject(value);
    const disabled = value === undefined || value === null;
    const unknown = !enabled && !disabled;
    const objectValue = enabled ? value as Record<string, unknown> : {};
    const diagnostics: EditDiagnostic[] = unknown
        ? [{
            severity: "error",
            message: options.unknownMessage ?? `Unknown ${key} value. Expected an object or omission.`,
            path,
        }]
        : [];

    return finalizeNode({
        id: `edit:${path.join(".")}`,
        path,
        label: `${key}: < ${enabled ? options.enabledLabel : options.disabledLabel} >`,
        value: enabled ? options.enabledValue : options.disabledValue,
        valueKind: "union",
        presence: options.presence ?? "optional",
        expert: options.expert ?? false,
        description: options.description,
        status: unknown ? "error" : "ok",
        diagnostics,
        variants: [
            {
                label: options.disabledLabel,
                value: options.disabledValue,
                description: options.disabledDescription,
            },
            {
                label: options.enabledLabel,
                value: options.enabledValue,
                description: options.enabledDescription,
            },
        ],
        children: enabled
            ? schemaObjectChildren(path, schema, objectValue)
            : unknown ? objectChildrenFromValue(path, value) : [],
    });
}

export function discriminatorForSchema(schema: any): string | undefined {
    const discriminator = unwrapSchema(schema)?._def?.discriminator;
    return typeof discriminator === "string" ? discriminator : undefined;
}

export function discriminatedUnionBranches(schema: any, discriminator: string): DiscriminatedUnionBranch[] {
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

export function discriminatedUnionNode(
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

export function objectUnionNode(
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
    const branches = objectUnionBranches(schema);
    if (!branches.length) {
        return undefined;
    }

    const selected = isPlainObject(value)
        ? branches.find(branch => Object.hasOwn(value, branch.value))
        : undefined;
    const selectedLabel = selected
        ? objectUnionVariantLabel(path, key, selected.value)
        : undefined;
    const unset = !required && !hasValue && value === undefined;
    const hasObjectValue = value !== undefined && value !== null;
    const missing = required && !selected;
    const unknown = hasObjectValue && !selected;
    const diagnostics: EditDiagnostic[] = [];
    if (missing) {
        diagnostics.push({severity: "required", message: `${key} is required.`, path});
    } else if (unknown) {
        diagnostics.push({
            severity: "error",
            message: `Unknown ${key} variant. Expected ${branches.map(branch => branch.value).join(" or ")}.`,
            path,
        });
    }

    return finalizeNode({
        id: `edit:${path.join(".")}`,
        path,
        label: `${key}: < ${unset ? "unset" : selectedLabel ?? (required ? "required" : "unknown")} >`,
        value: unset ? "unset" : selected?.value,
        valueKind: "union",
        presence,
        expert,
        description,
        status: missing ? "required" : unknown ? "error" : "ok",
        diagnostics,
        variants: [
            ...(!required ? [{label: "unset", value: "unset", description: "Remove this optional configuration."}] : []),
            ...branches.map(branch => ({
                label: objectUnionVariantLabel(path, key, branch.value),
                value: branch.value,
                description: branch.description,
            })),
        ],
        children: selected
            ? schemaObjectChildren(path, selected.optionSchema, value)
            : isPlainObject(value) ? objectChildrenFromValue(path, value) : [],
    });
}

export function recordKeyHint(recordHint: EditInputHint | undefined): EditInputHint | undefined {
    return recordHint?.kind === "record" ? {
        kind: "text",
        format: recordHint.keyFormat ?? "text",
        pattern: recordHint.keyPattern,
        message: recordHint.message,
    } : undefined;
}

function addCount(counts: StatusCounts, status: EditNodeStatus | undefined, amount = 1): void {
    const key = status ? STATUS_COUNT_KEYS[status] : undefined;
    if (key) {
        counts[key] = (counts[key] ?? 0) + amount;
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
    return STATUS_PRIORITY.find(status => {
        const key = STATUS_COUNT_KEYS[status];
        return key && counts[key];
    }) ?? "ok";
}

export function finalizeNode(node: EditNode): EditNode {
    const counts: StatusCounts = {};
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
    return node;
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

export function applyValidationDiagnostics(nodes: EditNode[], diagnostics: EditDiagnostic[]): void {
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
            node.statusCounts = node.statusCounts ?? {};
            if (!alreadyRepresentedRequired) {
                addCount(node.statusCounts, severity);
            }
            node.status = highestStatus(node.status ?? "ok", severity);
        }
    }
}

function highestStatus(a: EditNodeStatus, b: EditNodeStatus): EditNodeStatus {
    return STATUS_PRIORITY.indexOf(a) <= STATUS_PRIORITY.indexOf(b) ? a : b;
}

export function scalarNode(
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
    const diagnostics: EditDiagnostic[] = missing
        ? [{severity: "required", message: `${key} is required.`, path}]
        : patternMismatch ? [{
            severity: "error",
            message: validation?.message ?? `${key} does not match the expected format.`,
            path,
        }] : [];
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

export function booleanNode(
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

export function objectRefNode(
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

export function objectChildrenFromValue(path: string[], value: unknown): EditNode[] {
    if (!isPlainObject(value)) {
        return [];
    }
    return Object.entries(value)
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([key, childValue]) => genericDisplayNode([...path, key], key, childValue, "optional", false, ""));
}

export function genericDisplayNode(
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

export function schemaFieldNode(
    rootPath: string[],
    key: string,
    schema: any,
    config: Record<string, unknown>,
    authoredConfig: Record<string, unknown> = config,
): EditNode {
    const path = [...rootPath, key];
    const description = schemaDescription(schema);
    const required = isRequiredSchema(schema);
    const defaultValue = defaultValueForSchema(schema);
    const userRequired = zodSchemaRequiredForUser(schema, required, defaultValue);
    const presence: EditNode["presence"] = userRequired ? "required" : "optional";
    const expert = expertOf(schema) || isExpertDescription(description);
    const essential = essentialOf(schema);
    const {hasValue, value, valueDefaulted} = effectiveConfigValue(config, key, defaultValue, authoredConfig);
    const authoredValue = hasValue ? authoredConfig[key] : undefined;
    const inputHint = uiHintOf(schema);
    const externalRef = externalRefOf(schema);
    const scalarType = schemaScalarType(schema);
    const mark = (node: EditNode) => markValueState(node, valueDefaulted, hasValue, essential);

    if (schemaConstructorName(schema) === "ZodArray") {
        const jsonSchema = jsonSchemaForConfigPath(path);
        if (jsonSchema && jsonSchemaType(jsonSchema) === "array") {
            return mark(jsonSchemaFieldNode(rootPath, key, jsonSchema, config, required));
        }
        return mark(zodArrayNode(path, key, schema, value, userRequired, inputHint, description, expert, presence));
    }
    if (schemaConstructorName(schema) === "ZodRecord") {
        const jsonSchema = jsonSchemaForConfigPath(path);
        if (jsonSchema && (
            Object.keys(jsonSchemaProperties(jsonSchema)).length > 0
            || jsonSchemaBranches(jsonSchema).length > 0
            || jsonSchemaEnumValues(jsonSchema).length > 0
        )) {
            return mark(jsonSchemaFieldNode(rootPath, key, jsonSchema, config, required));
        }
        return mark(zodRecordNode(
            path,
            key,
            schema,
            value,
            userRequired,
            inputHint,
            description,
            expert,
            presence,
        ));
    }
    const unionNode = discriminatedUnionNode(path, key, schema, value, hasValue, description, userRequired, expert, presence);
    if (unionNode) {
        return mark(unionNode);
    }
    const objectVariantNode = objectUnionNode(path, key, schema, value, hasValue, description, userRequired, expert, presence);
    if (objectVariantNode) {
        return mark(objectVariantNode);
    }
    if (zodEnumValues(schema).length > 0) {
        return mark(zodEnumNode(path, key, schema, value, userRequired, expert, presence, defaultValue));
    }
    if (scalarType === "boolean") {
        return mark(booleanNode(path, key, value === true, description, expert, presence));
    }
    if (scalarType === "number") {
        return mark(scalarNode(path, key, value, description, userRequired, inputHint, "number", expert, presence, externalRef));
    }
    if (scalarType === "string") {
        return mark(scalarNode(path, key, value ?? "", description, userRequired, inputHint, "string", expert, presence, externalRef));
    }
    if (externalRef?.selection?.target === "objectRef") {
        return mark(objectRefNode(path, key, value, description, userRequired, externalRef));
    }
    if (schemaShape(schema)) {
        if (isPlainObject(value)) {
            return mark(zodObjectNode(path, key, schema, value, authoredValue, userRequired, description, expert, presence));
        }
        const jsonSchema = jsonSchemaForConfigPath(path);
        if (jsonSchema && (
            Object.keys(jsonSchemaProperties(jsonSchema)).length > 0
            || jsonSchemaBranches(jsonSchema).length > 0
            || jsonSchemaEnumValues(jsonSchema).length > 0
            || jsonSchemaType(jsonSchema) === "object"
        )) {
            return mark(jsonSchemaFieldNode(rootPath, key, jsonSchema, config, required));
        }
    }
    if (value === undefined || value === null) {
        const containerKind = schemaContainerKind(schema);
        if (containerKind) {
            return mark(finalizeNode({
                id: `edit:${path.join(".")}`,
                path,
                label: `${key}: <unset>`,
                value,
                valueKind: containerKind,
                presence,
                expert,
                description,
                status: "ok",
            }));
        }
        return mark(scalarNode(path, key, "", description, userRequired, inputHint, "string", expert, presence, externalRef));
    }
    return mark(genericDisplayNode(path, key, value, presence, expert, description));
}

function zodRecordNode(
    path: string[],
    key: string,
    schema: any,
    value: unknown,
    required: boolean,
    inputHint: EditInputHint | undefined,
    description: string,
    expert: boolean,
    presence: EditNode["presence"],
): EditNode {
    const recordValue = isPlainObject(value) ? value : {};
    const valueSchema = zodRecordValueSchema(schema);
    const addLabel = recordAddLabel(inputHint);
    const children = [
        ...Object.entries(recordValue)
            .sort(([a], [b]) => a.localeCompare(b))
            .map(([recordKey, recordItemValue]) => valueSchema
                ? schemaFieldNode(path, recordKey, valueSchema, {[recordKey]: recordItemValue})
                : genericDisplayNode([...path, recordKey], recordKey, recordItemValue, "required", false, "")),
        addRow(
            path,
            addLabel,
            arrayDescriptionForAdd(addLabel),
            true,
            recordKeyHint(inputHint),
            expert,
            true,
        ),
    ];
    const missing = required && (value === undefined || value === null);
    return finalizeNode({
        id: `edit:${path.join(".")}`,
        path,
        label: `${key}: ${Object.keys(recordValue).length} item${Object.keys(recordValue).length === 1 ? "" : "s"}`,
        value,
        valueKind: "record",
        presence,
        expert,
        description,
        required,
        inputHint,
        status: missing ? "required" : "ok",
        diagnostics: missing ? [{severity: "required", message: `${key} is required.`, path}] : [],
        children,
    });
}

function zodObjectNode(
    path: string[],
    key: string,
    schema: any,
    value: Record<string, unknown>,
    authoredValue: unknown,
    required: boolean,
    description: string,
    expert: boolean,
    presence: EditNode["presence"],
): EditNode {
    const shape = schemaShape(schema) ?? {};
    const authoredObject = isPlainObject(authoredValue) ? authoredValue : {};
    const knownKeys = new Set(Object.keys(shape));
    const children = [
        ...Object.entries(shape).map(([childKey, childSchema]) =>
            schemaFieldNode(path, childKey, childSchema, value, authoredObject)
        ),
        ...Object.keys(value)
            .filter(childKey => !knownKeys.has(childKey))
            .sort((a, b) => a.localeCompare(b))
            .map(childKey => genericDisplayNode(
                [...path, childKey],
                childKey,
                value[childKey],
                "optional",
                false,
                "",
            )),
    ];
    const missing = required && (value === undefined || value === null);
    return finalizeNode({
        id: `edit:${path.join(".")}`,
        path,
        label: `${key}: ${Object.keys(value).length ? "configured" : "{}"}`,
        value,
        valueKind: "object",
        presence,
        expert,
        description,
        required,
        status: missing ? "required" : "ok",
        diagnostics: missing ? [{severity: "required", message: `${key} is required.`, path}] : [],
        children,
    });
}

function zodArrayNode(
    path: string[],
    key: string,
    schema: any,
    value: unknown,
    required: boolean,
    inputHint: EditInputHint | undefined,
    description: string,
    expert: boolean,
    presence: EditNode["presence"],
): EditNode {
    const arrayValue = Array.isArray(value) ? value : [];
    const itemSchema = schemaArrayElement(schema);
    const addLabel = arrayAddLabel(inputHint);
    const children = [
        ...arrayValue.map((itemValue, index) => zodArrayItemNode(path, itemSchema, itemValue, index, addLabel)),
        addRow(path, addLabel, arrayDescriptionForAdd(addLabel), false),
    ];
    const missing = required && (value === undefined || value === null);
    return finalizeNode({
        id: `edit:${path.join(".")}`,
        path,
        label: `${key}: ${value === undefined || value === null ? (required ? "<required>" : "<unset>") : `${arrayValue.length} item${arrayValue.length === 1 ? "" : "s"}`}`,
        value,
        valueKind: "array",
        presence,
        expert,
        description,
        required,
        inputHint,
        status: missing ? "required" : "ok",
        diagnostics: missing ? [{severity: "required", message: `${key} is required.`, path}] : [],
        children,
    });
}

function zodArrayItemNode(
    rootPath: string[],
    schema: any,
    value: unknown,
    index: number,
    itemLabel: string,
): EditNode {
    const key = String(index);
    const node = schema
        ? schemaFieldNode(rootPath, key, schema, {[key]: value})
        : genericDisplayNode([...rootPath, key], key, value, "required", false, "");
    const label = node.label;
    const valueSuffix = label.includes(":") ? label.slice(label.indexOf(":")) : "";
    node.label = `${itemLabel} ${index + 1}${valueSuffix}`;
    node.collapsed = true;
    return node;
}

export function addRow(
    path: string[],
    label: string,
    description: string,
    requiresName = true,
    inputHint?: EditInputHint,
    expert = false,
    editAdded = false,
    autoEditAdded = true,
): EditNode {
    return finalizeNode({
        id: `edit:${path.join(".")}:add`,
        path,
        label: `+ Add ${label}`,
        valueKind: "command",
        description,
        expert,
        inputHint,
        validation: validationFromHint(inputHint),
        command: {requiresName, editAdded, autoEditAdded},
        status: "ok",
    });
}

export function isArrayIndex(part: string): boolean {
    const index = Number(part);
    return Number.isInteger(index) && index >= 0;
}

export function childSchemaAtPath(schema: any, path: string[]): any | undefined {
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

    const objectBranch = objectUnionBranches(schema).find(branch => Boolean(schemaShape(branch.optionSchema)?.[part]));
    if (objectBranch) {
        const branchShape = schemaShape(objectBranch.optionSchema);
        return childSchemaAtPath(branchShape?.[part], rest);
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

    if (schemaConstructorName(unwrapped) === "ZodRecord") {
        const valueSchema = unwrapped?.valueType ?? unwrapped?._def?.valueType;
        if (valueSchema) {
            return childSchemaAtPath(valueSchema, rest);
        }
    }

    return undefined;
}

export function singleKeyUnionValueForVariant(schema: any, existing: any, variant: unknown): unknown {
    const branch = singleKeyUnionBranches(schema).find(item => item.value === variant);
    if (!branch) {
        throw new Error(`Unknown variant: ${String(variant)}`);
    }
    return {
        [branch.value]: isPlainObject(existing?.[branch.value]) ? existing[branch.value] : {},
    };
}

export function objectUnionValueForVariant(schema: any, existing: any, variant: unknown): unknown {
    if (isUnsetVariant(variant)) {
        return undefined;
    }
    const branch = objectUnionBranches(schema).find(item => item.value === variant);
    if (!branch) {
        throw new Error(`Unknown variant: ${String(variant)}`);
    }
    const shape = schemaShape(branch.optionSchema) ?? {};
    const next: Record<string, unknown> = {};
    for (const [key, fieldSchema] of Object.entries(shape)) {
        if (isPlainObject(existing) && Object.hasOwn(existing, key)) {
            next[key] = existing[key];
            continue;
        }
        const defaultValue = defaultValueForSchema(fieldSchema);
        if (defaultValue !== undefined) {
            next[key] = defaultValue;
        } else if (isRequiredSchema(fieldSchema)) {
            next[key] = emptyRequiredValueForSchema(fieldSchema);
        }
    }
    return next;
}

function emptyRequiredValueForSchema(schema: any): unknown {
    const scalarType = schemaScalarType(schema);
    if (scalarType === "number") {
        return 0;
    }
    if (scalarType === "boolean") {
        return false;
    }
    if (schemaConstructorName(schema) === "ZodArray") {
        return [];
    }
    if (
        schemaShape(schema)
        || schemaConstructorName(schema) === "ZodRecord"
        || objectUnionBranches(schema).length > 0
        || discriminatorForSchema(schema)
    ) {
        return {};
    }
    return "";
}

function isUnsetVariant(variant: unknown): boolean {
    return variant === "unset" || variant === null || variant === undefined || variant === "";
}

function valueForDiscriminatedUnionBranch(
    discriminator: string,
    existing: any,
    variant: unknown,
    fields: Array<[string, any]>,
    defaultForField: (schema: any) => unknown,
): Record<string, unknown> {
    const next: Record<string, unknown> = {[discriminator]: variant};
    for (const [key, fieldSchema] of fields) {
        if (key === discriminator) {
            continue;
        }
        if (isPlainObject(existing) && Object.hasOwn(existing, key)) {
            next[key] = existing[key];
            continue;
        }
        const defaultValue = defaultForField(fieldSchema);
        if (defaultValue !== undefined) {
            next[key] = defaultValue;
        }
    }
    return next;
}

export function discriminatedUnionValueForVariant(schema: any, existing: any, variant: unknown): unknown {
    if (isUnsetVariant(variant)) {
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

    return valueForDiscriminatedUnionBranch(
        discriminator,
        existing,
        variant,
        Object.entries(schemaShape(branch.optionSchema) ?? {}),
        defaultValueForSchema,
    );
}

export function jsonDiscriminatedUnionValueForVariant(schema: JsonSchema, existing: any, variant: unknown): unknown {
    if (isUnsetVariant(variant)) {
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

    return valueForDiscriminatedUnionBranch(
        discriminator,
        existing,
        variant,
        Object.entries(jsonSchemaProperties(branch)),
        fieldSchema => resolveJsonSchemaRef(fieldSchema)?.default,
    );
}

export function jsonObjectUnionValueForVariant(schema: JsonSchema, existing: any, variant: unknown): unknown {
    if (isUnsetVariant(variant)) {
        return undefined;
    }
    const branch = jsonSchemaObjectUnionBranches(schema).find(item => item.value === variant);
    if (!branch) {
        throw new Error(`Unknown variant: ${String(variant)}`);
    }
    const next: Record<string, unknown> = {};
    const requiredKeys = jsonSchemaRequired(branch.optionSchema);
    for (const [key, fieldSchema] of Object.entries(jsonSchemaProperties(branch.optionSchema))) {
        if (isPlainObject(existing) && Object.hasOwn(existing, key)) {
            next[key] = existing[key];
            continue;
        }
        const resolvedField = resolveJsonSchemaRef(fieldSchema);
        if (resolvedField?.default !== undefined) {
            next[key] = structuredClone(resolvedField.default);
        } else if (requiredKeys.has(key)) {
            next[key] = defaultJsonValueForSchema(resolvedField);
        }
    }
    return next;
}
