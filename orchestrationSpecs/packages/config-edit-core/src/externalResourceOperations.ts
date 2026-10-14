import type {EditNode, EditOperation} from "./schemaEditModel";


export interface ExternalResourceSelectionValue {
    group: string;
    key?: string | null;
    kind: string;
    name: string;
}


function record(value: unknown): Record<string, unknown> {
    return value && typeof value === "object" && !Array.isArray(value)
        ? value as Record<string, unknown>
        : {};
}


export function externalResourceSelectionOperations(
    node: EditNode,
    selected: ExternalResourceSelectionValue,
): EditOperation[] {
    const selection = record(record(node.externalRef).selection);
    const target = typeof selection.target === "string"
        ? selection.target
        : "scalarName";
    if (target === "scalarName") {
        return [{op: "set", path: node.path, value: selected.name}];
    }
    if (target === "objectRef") {
        const value: Record<string, string> = {
            [typeof selection.nameField === "string"
                ? selection.nameField
                : "name"]: selected.name,
            [typeof selection.kindField === "string"
                ? selection.kindField
                : "kind"]: selected.kind,
        };
        if (selected.group) {
            value[typeof selection.groupField === "string"
                ? selection.groupField
                : "group"] = selected.group;
        }
        return [{op: "set", path: node.path, value}];
    }
    if (target === "fileRefConfigMap") {
        if (!selected.key) {
            throw new Error("A ConfigMap key must be selected.");
        }
        const parentPath = node.path.slice(0, -1);
        return [
            {
                op: "set",
                path: [
                    ...parentPath,
                    typeof selection.nameField === "string"
                        ? selection.nameField
                        : node.path.at(-1) ?? "configMap",
                ],
                value: selected.name,
            },
            {
                op: "set",
                path: [
                    ...parentPath,
                    typeof selection.pathField === "string"
                        ? selection.pathField
                        : "path",
                ],
                value: selected.key,
            },
        ];
    }
    throw new Error(`Unsupported external selection target: ${target}`);
}


export function createdExternalResourceOperations(
    node: EditNode,
    values: Record<string, string>,
    name: string,
): EditOperation[] {
    const externalRef = record(node.externalRef);
    const create = record(externalRef.create);
    const apply = record(create.apply);
    const target = typeof apply.target === "string"
        ? apply.target
        : "scalarName";
    if (target === "scalarName") {
        return [{op: "set", path: node.path, value: name}];
    }
    if (target === "fileRefConfigMap") {
        const selection = record(externalRef.selection);
        if (selection.target !== "fileRefConfigMap") {
            throw new Error(
                "The file reference is missing its ConfigMap selection descriptor.",
            );
        }
        const keyField = typeof apply.pathField === "string"
            ? apply.pathField
            : "";
        const key = keyField ? values[keyField] : "";
        if (!key) {
            throw new Error("A ConfigMap key is required.");
        }
        const parentPath = node.path.slice(0, -1);
        return [
            {
                op: "set",
                path: [
                    ...parentPath,
                    typeof selection.nameField === "string"
                        ? selection.nameField
                        : node.path.at(-1) ?? "configMap",
                ],
                value: name,
            },
            {
                op: "set",
                path: [
                    ...parentPath,
                    typeof selection.pathField === "string"
                        ? selection.pathField
                        : "path",
                ],
                value: key,
            },
        ];
    }
    throw new Error(`Unsupported external create target: ${target}`);
}
