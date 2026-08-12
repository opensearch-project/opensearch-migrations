import {
  type ConfigControl,
  type ManageTreeState,
  type TreeNode,
} from "@manage-spike/shared";

export type ConfigField = TreeNode & { configControl: ConfigControl };

export function collectConfigFields(
  state: ManageTreeState,
  selectedNode: TreeNode,
): ReadonlyArray<ConfigField> {
  const fields: ConfigField[] = [];
  const visit = (nodeId: string): void => {
    const node = state.nodes[nodeId];
    if (!node) {
      return;
    }
    if (node.kind === "config-field" && node.configControl) {
      fields.push(node as ConfigField);
    }
    node.childIds.forEach(visit);
  };
  visit(selectedNode.id);
  return fields;
}

export function collectDiagnostics(
  nodes: Readonly<Record<string, TreeNode>>,
  selectedNode: TreeNode,
): ReadonlyArray<TreeNode> {
  const diagnostics: TreeNode[] = [];
  const visit = (nodeId: string): void => {
    const node = nodes[nodeId];
    if (!node) {
      return;
    }
    if (node.kind === "diagnostic") {
      diagnostics.push(node);
    }
    node.childIds.forEach(visit);
  };
  selectedNode.childIds.forEach(visit);
  return diagnostics;
}

export function assertNever(value: never): never {
  throw new Error(`Unhandled value: ${JSON.stringify(value)}`);
}
