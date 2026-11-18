import type { EditNode } from "@opensearch-migrations/config-edit-core";


export interface ResourceAddPlacement {
  addControlId?: string;
  collectionPath: string;
  groupId: string;
  groupLabel: string;
  nodeKind: "resource" | "config-definition";
  resourcePlural?: string;
  resourceType: string;
  sectionId?: string;
  sectionLabel?: string;
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
  collisionScope?: string;
  conflictingNames?: string[];
  currentName: string;
  editTargetStable?: boolean;
  editTargetId: string;
  label: string;
  labelPrefix?: string;
  operation?: "renameConfig" | "set";
  path: string[];
  pattern?: string;
  placement: ResourceAddPlacement;
  resourceNamePrefix?: string;
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
  groupLabel: string;
  label: string;
  nodeKind: "resource" | "config-definition";
  resourceName: string;
  resourcePlural?: string;
  resourceType: string;
  sectionId?: string;
  sectionLabel?: string;
  status: "syncing" | "awaiting-draft";
}


export interface PendingResourceRename {
  editTargetId: string;
  groupId: string;
  id: string;
  label: string;
  nodeKind: "resource" | "config-definition";
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
  if (collection) {
    const { navigation, resource } = collection;
    const directSectionPlacement = resource.plural === "snapshotmigrations";
    return {
      addControlId: navigation.addControlId ?? undefined,
      collectionPath: node.path.join("."),
      groupId: directSectionPlacement
        ? navigation.sectionId
        : navigation.groupId,
      groupLabel: directSectionPlacement
        ? navigation.sectionLabel
        : navigation.groupLabel,
      nodeKind: "resource",
      resourcePlural: resource.plural,
      resourceType: resource.typeLabel,
      sectionId: navigation.sectionId,
      sectionLabel: navigation.sectionLabel,
    };
  }
  const definition = node.inputHint?.definitionCollection;
  if (!definition?.navigation.groupId) return null;
  return {
    collectionPath: node.path.join("."),
    groupId: definition.navigation.groupId,
    groupLabel: definition.navigation.groupLabel,
    nodeKind: "config-definition",
    resourceType: definition.definition.typeLabel,
  };
}


export function pendingResourceAddition(
  option: ResourceAddOption,
  name: string,
  index: number,
): PendingResourceAddition {
  const namedArrayItem = option.placement.collectionPath
    === "snapshotMigrationConfigs";
  const targetKey = option.requiresName && !namedArrayItem
    ? name
    : String(index);
  const editTargetId = `edit:${option.placement.collectionPath}.${targetKey}`;
  const label = option.requiresName
    ? name
    : `${option.label.replace(/^Add\s+/i, "")} ${index + 1}`;
  return {
    id: `optimistic-add:${editTargetId}`,
    editTargetId,
    groupId: option.placement.groupId,
    groupLabel: option.placement.groupLabel,
    label,
    nodeKind: option.placement.nodeKind,
    resourceName: label,
    resourcePlural: option.placement.resourcePlural,
    resourceType: option.placement.resourceType,
    ...(option.placement.sectionId && option.placement.sectionLabel ? {
      sectionId: option.placement.sectionId,
      sectionLabel: option.placement.sectionLabel,
    } : {}),
    status: "syncing",
  };
}


export function pendingResourceRename(
  option: ResourceRenameOption,
  resourceId: string,
  newName: string,
): PendingResourceRename {
  const editTargetId = option.editTargetStable
    ? option.editTargetId
    : `edit:${[
      ...option.path.slice(0, -1),
      newName,
    ].join(".")}`;
  const label = `${option.labelPrefix ?? ""}${newName}`;
  const resourceName = `${option.resourceNamePrefix ?? ""}${newName}`;
  let id = `optimistic-rename:${editTargetId}`;
  if (option.editTargetStable) {
    id = resourceId;
  } else if (option.placement.resourcePlural && option.resourceNamePrefix) {
    id = `resource:${option.placement.resourcePlural}:${resourceName}`;
  }
  return {
    id,
    editTargetId,
    groupId: option.placement.groupId,
    label,
    nodeKind: option.placement.nodeKind,
    oldEditTargetId: option.editTargetId,
    oldId: resourceId,
    resourceName,
    resourcePlural: option.placement.resourcePlural,
    resourceType: option.placement.resourceType,
    status: "syncing",
  };
}


export function resourceRenameCollisionProblem(
  option: ResourceRenameOption | undefined,
  name: string,
): string {
  const candidate = name.trim();
  if (
    !option?.collisionScope
    || !candidate
    || candidate === option.currentName
    || !option.conflictingNames?.includes(candidate)
  ) {
    return "";
  }
  return `Snapshot migration '${
    option.collisionScope
  }-${candidate}' is already configured. Choose another name.`;
}
