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
    valueType?: "string" | "number" | "boolean";
    valueKind: "object" | "record" | "array" | "union" | "boolean" | "scalar" | "command";
    presence?: "required" | "optional";
    expert?: boolean;
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
    ZodEnum: "string",
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

function jsonSchemaInputHint(schema: JsonSchema | undefined): EditInputHint | undefined {
    const resolved = resolveJsonSchemaRef(schema);
    const pattern = typeof resolved?.pattern === "string" ? resolved.pattern : undefined;
    return pattern ? {
        kind: "text",
        pattern,
        message: "Value does not match the expected format.",
    } : undefined;
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
    const label = node.label;
    const valueSuffix = label.includes(":") ? label.slice(label.indexOf(":")) : "";
    node.label = `item ${index + 1}${valueSuffix}`;
    node.collapsed = true;
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
    const {hasValue, value, valueDefaulted} = effectiveConfigValue(config, key, resolved.default);
    const description = jsonSchemaDescription(resolved);
    const presence: EditNode["presence"] = required ? "required" : "optional";
    const expert = isExpertDescription(description);
    const unionNode = jsonSchemaUnionNode(path, key, resolved, value, hasValue, required, expert, presence);
    if (unionNode) {
        return markValueDefaulted(unionNode, valueDefaulted);
    }
    if (jsonSchemaEnumValues(resolved).length > 0) {
        return markValueDefaulted(jsonEnumNode(path, key, resolved, value, required, expert, presence), valueDefaulted);
    }
    const valueType = jsonScalarValueType(resolved);
    if (valueType === "boolean") {
        return markValueDefaulted(booleanNode(path, key, value === true, description, expert, presence), valueDefaulted);
    }
    if (valueType === "number" || valueType === "string") {
        return markValueDefaulted(scalarNode(path, key, value ?? "", description, required, jsonSchemaInputHint(resolved), valueType, expert, presence), valueDefaulted);
    }

    const containerKind = jsonSchemaType(resolved) === "array" ? "array" : "object";
    const childNodes = containerKind === "object"
        ? jsonSchemaObjectChildren(path, resolved, value)
        : jsonSchemaArrayChildren(path, resolved, value);
    if (childNodes.length || value === undefined || value === null || isPlainObject(value) || Array.isArray(value)) {
        return markValueDefaulted(finalizeNode({
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
        }), valueDefaulted);
    }

    return markValueDefaulted(genericDisplayNode(path, key, value, presence, expert, description), valueDefaulted);
}

function markValueDefaulted(node: EditNode, valueDefaulted: boolean): EditNode {
    if (valueDefaulted) {
        node.valueDefaulted = true;
    }
    return node;
}

function effectiveConfigValue(config: Record<string, unknown>, key: string, defaultValue: unknown) {
    const hasValue = Object.hasOwn(config, key);
    return {
        hasValue,
        value: hasValue ? config[key] : defaultValue,
        valueDefaulted: !hasValue && shouldRenderSchemaDefault(defaultValue),
    };
}

function shouldRenderSchemaDefault(value: unknown): boolean {
    if (value === undefined || value === null || value === "") {
        return false;
    }
    if (Array.isArray(value) && value.length === 0) {
        return false;
    }
    if (isPlainObject(value) && Object.keys(value).length === 0) {
        return false;
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
): EditNode {
    const path = [...rootPath, key];
    const description = schemaDescription(schema);
    const required = isRequiredSchema(schema);
    const presence: EditNode["presence"] = required ? "required" : "optional";
    const expert = isExpertDescription(description);
    const {hasValue, value, valueDefaulted} = effectiveConfigValue(config, key, defaultValueForSchema(schema));
    const inputHint = uiHintOf(schema);
    const externalRef = externalRefOf(schema);
    const scalarType = schemaScalarType(schema);

    if (schemaConstructorName(schema) === "ZodRecord") {
        const jsonSchema = jsonSchemaForConfigPath(path);
        if (jsonSchema && (
            Object.keys(jsonSchemaProperties(jsonSchema)).length > 0
            || jsonSchemaBranches(jsonSchema).length > 0
            || jsonSchemaEnumValues(jsonSchema).length > 0
        )) {
            return jsonSchemaFieldNode(rootPath, key, jsonSchema, config, required);
        }
    }
    const unionNode = discriminatedUnionNode(path, key, schema, value, hasValue, description, required, expert, presence);
    if (unionNode) {
        return markValueDefaulted(unionNode, valueDefaulted);
    }
    if (scalarType === "boolean") {
        return markValueDefaulted(booleanNode(path, key, value === true, description, expert, presence), valueDefaulted);
    }
    if (scalarType === "number") {
        return markValueDefaulted(scalarNode(path, key, value, description, required, inputHint, "number", expert, presence, externalRef), valueDefaulted);
    }
    if (scalarType === "string") {
        return markValueDefaulted(scalarNode(path, key, value ?? "", description, required, inputHint, "string", expert, presence, externalRef), valueDefaulted);
    }
    if (externalRef?.selection?.target === "objectRef") {
        return markValueDefaulted(objectRefNode(path, key, value, description, required, externalRef), valueDefaulted);
    }
    if (value === undefined || value === null) {
        const containerKind = schemaContainerKind(schema);
        if (containerKind) {
            return markValueDefaulted(finalizeNode({
                id: `edit:${path.join(".")}`,
                path,
                label: `${key}: <unset>`,
                value,
                valueKind: containerKind,
                presence,
                expert,
                description,
                status: "ok",
            }), valueDefaulted);
        }
        return markValueDefaulted(scalarNode(path, key, "", description, required, inputHint, "string", expert, presence, externalRef), valueDefaulted);
    }
    return markValueDefaulted(genericDisplayNode(path, key, value, presence, expert, description), valueDefaulted);
}

export function addRow(
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

export function singleKeyUnionValueForVariant(schema: any, existing: any, variant: unknown): unknown {
    const branch = singleKeyUnionBranches(schema).find(item => item.value === variant);
    if (!branch) {
        throw new Error(`Unknown variant: ${String(variant)}`);
    }
    return {
        [branch.value]: isPlainObject(existing?.[branch.value]) ? existing[branch.value] : {},
    };
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
