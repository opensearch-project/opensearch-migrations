import type { EditNode } from "../../api/client";


export interface ResourceAddPlacement {
  addControlId?: string;
  collectionPath: string;
  groupId: string;
  resourcePlural: string;
  resourceType: string;
}


export interface ResourceAddOption {
  id: string;
  label: string;
  disabled: boolean;
  disabledReason?: string;
  placement: ResourceAddPlacement;
  requiresName: boolean;
  pattern?: string;
  validationMessage?: string;
}


export interface ResourceRenameOption {
  currentName: string;
  editTargetId: string;
  label: string;
  path: string[];
  pattern?: string;
  placement: ResourceAddPlacement;
  validationMessage?: string;
}


export interface ResourceAddController {
  options: ResourceAddOption[];
  renames: ResourceRenameOption[];
  busy: boolean;
  add: (optionId: string, name: string) => Promise<boolean>;
  rename: (
    editTargetId: string,
    resourceId: string,
    newName: string,
  ) => Promise<boolean>;
}


export interface PendingResourceAddition {
  id: string;
  editTargetId: string;
  groupId: string;
  label: string;
  resourceName: string;
  resourcePlural: string;
  resourceType: string;
  status: "syncing" | "awaiting-draft";
}


export interface PendingResourceRename {
  editTargetId: string;
  groupId: string;
  id: string;
  label: string;
  oldEditTargetId: string;
  oldId: string;
  resourceName: string;
  resourcePlural: string;
  resourceType: string;
  status: "syncing" | "applied";
}


export function resourceAddPlacement(
  node: Pick<EditNode, "inputHint" | "path">,
): ResourceAddPlacement | null {
  const collection = node.inputHint?.resourceCollection;
  if (!collection) return null;
  const { navigation, resource } = collection;
  return {
    addControlId: navigation.addControlId ?? undefined,
    collectionPath: node.path.join("."),
    groupId: navigation.groupId,
    resourcePlural: resource.plural,
    resourceType: resource.typeLabel,
  };
}


export function pendingResourceAddition(
  option: ResourceAddOption,
  name: string,
  index: number,
): PendingResourceAddition {
  const targetKey = option.requiresName ? name : String(index);
  const editTargetId = `edit:${option.placement.collectionPath}.${targetKey}`;
  const label = option.requiresName
    ? name
    : `${option.label.replace(/^Add\s+/i, "")} ${index + 1}`;
  return {
    id: `optimistic-add:${editTargetId}`,
    editTargetId,
    groupId: option.placement.groupId,
    label,
    resourceName: label,
    resourcePlural: option.placement.resourcePlural,
    resourceType: option.placement.resourceType,
    status: "syncing",
  };
}


export function pendingResourceRename(
  option: ResourceRenameOption,
  resourceId: string,
  newName: string,
): PendingResourceRename {
  const editTargetId = `edit:${[
    ...option.path.slice(0, -1),
    newName,
  ].join(".")}`;
  return {
    id: `optimistic-rename:${editTargetId}`,
    editTargetId,
    groupId: option.placement.groupId,
    label: newName,
    oldEditTargetId: option.editTargetId,
    oldId: resourceId,
    resourceName: newName,
    resourcePlural: option.placement.resourcePlural,
    resourceType: option.placement.resourceType,
    status: "syncing",
  };
}
