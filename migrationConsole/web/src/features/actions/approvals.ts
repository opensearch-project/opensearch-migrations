import type { ManageNode, ManageSnapshot } from "../../api/client";


export interface ApprovalCandidate {
  disabledReason: string | null;
  editTargetId: string | null;
  immutable: boolean;
  immutableReason: string | null;
  label: string;
  nodeId: string;
  nodeLabel: string;
  outputTargetId: string | null;
  resetTargetId: string | null;
  resourcePresent: boolean;
  targetId: string;
}


function owningResource(
  snapshot: ManageSnapshot,
  node: ManageNode,
): ManageNode {
  let current = node;
  while (current.parentId) {
    const parent = snapshot.nodes[current.parentId];
    if (!parent) break;
    current = parent;
    if (current.kind === "resource") return current;
  }
  return node;
}


export function approvalCandidates(
  snapshot: ManageSnapshot | undefined,
): ApprovalCandidate[] {
  if (!snapshot) return [];
  const candidates = new Map<string, ApprovalCandidate>();
  const visit = (nodeId: string) => {
    const node = snapshot.nodes[nodeId];
    if (!node) return;
    node.capabilities.forEach((capability) => {
      if (capability.kind !== "approve") return;
      const owner = owningResource(snapshot, node);
      const immutableDiagnostic = owner.diagnostics.find(
        (diagnostic) => diagnostic.code === "immutable-resource-update",
      ) ?? node.diagnostics.find(
        (diagnostic) => diagnostic.code === "immutable-resource-update",
      );
      const editCapability = owner.capabilities.find(
        (candidate) => candidate.kind === "edit",
      );
      const resetCapability = owner.capabilities.find(
        (candidate) => candidate.kind === "reset",
      );
      const candidate = {
        disabledReason: capability.disabledReason ?? null,
        editTargetId: editCapability?.kind === "edit"
          ? editCapability.editTargetId
          : null,
        immutable: Boolean(immutableDiagnostic),
        immutableReason: immutableDiagnostic?.message ?? null,
        label: capability.label,
        nodeId: owner.id,
        nodeLabel: owner.label,
        outputTargetId: capability.outputTargetId ?? null,
        resetTargetId: resetCapability?.kind === "reset"
          ? resetCapability.resetTargetId
          : null,
        resourcePresent: (
          owner.configPresence?.deployed
          ?? Boolean(resetCapability)
        ),
        targetId: capability.approvalTargetId,
      };
      const previous = candidates.get(candidate.targetId);
      if (!previous || node.kind === "resource") {
        candidates.set(candidate.targetId, candidate);
      }
    });
    node.childIds.forEach(visit);
  };
  snapshot.rootIds.forEach(visit);
  return [...candidates.values()];
}


export function actionableApprovalCandidates(
  snapshot: ManageSnapshot | undefined,
): ApprovalCandidate[] {
  return approvalCandidates(snapshot).filter(
    (candidate) => !candidate.disabledReason,
  );
}
