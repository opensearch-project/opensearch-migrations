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
  resourceId: string;
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
  status: "syncing" | "applied";
}


const RESOURCE_ADD_PLACEMENTS: readonly ResourceAddPlacement[] = [
  {
    collectionPath: "sourceClusters",
    groupId: "group:Sources:Sources",
    resourcePlural: "sourceconfigs",
    resourceType: "Source cluster",
  },
  {
    collectionPath: "targetClusters",
    groupId: "group:Targets:Targets",
    resourcePlural: "targetconfigs",
    resourceType: "Target cluster",
  },
  {
    addControlId: "section:Snapshot Migration",
    collectionPath: "snapshotMigrationConfigs",
    groupId: "group:Snapshot Migration:Backfill",
    resourcePlural: "snapshotmigrations",
    resourceType: "Snapshot migration",
  },
  {
    collectionPath: "traffic.kafkaClusters",
    groupId: "group:Live Traffic Migration:Buffer",
    resourcePlural: "kafkaclusters",
    resourceType: "Kafka cluster",
  },
  {
    collectionPath: "traffic.s3Sources",
    groupId: "group:Live Traffic Migration:Buffer",
    resourcePlural: "capturedtraffics",
    resourceType: "S3 source",
  },
  {
    collectionPath: "traffic.proxies",
    groupId: "group:Live Traffic Migration:Capture",
    resourcePlural: "captureproxies",
    resourceType: "Capture proxy",
  },
  {
    collectionPath: "traffic.replayers",
    groupId: "group:Live Traffic Migration:Replay",
    resourcePlural: "trafficreplays",
    resourceType: "Traffic replay",
  },
];


export function resourceAddPlacement(
  path: readonly string[],
): ResourceAddPlacement | null {
  const key = path.join(".");
  return RESOURCE_ADD_PLACEMENTS.find(
    (placement) => placement.collectionPath === key,
  ) ?? null;
}


export function resourceAddPlacements(): readonly ResourceAddPlacement[] {
  return RESOURCE_ADD_PLACEMENTS;
}


export function resourceAdditionIdentity(
  placement: ResourceAddPlacement,
  name: string,
  index: number,
) {
  const isSnapshotMigration = (
    placement.collectionPath === "snapshotMigrationConfigs"
  );
  const authoredName = isSnapshotMigration
    ? `migration-${index + 1}`
    : name || `migration-${index + 1}`;
  const resourceName = placement.collectionPath === "traffic.s3Sources"
    ? `${authoredName}-topic`
    : authoredName;
  return {
    id: isSnapshotMigration
      ? `config:snapshotMigrationConfigs:${index}`
      : `resource:${placement.resourcePlural}:${resourceName}`,
    editTargetId: `edit:${[
      ...placement.collectionPath.split("."),
      isSnapshotMigration ? String(index) : authoredName,
    ].join(".")}`,
    label: resourceName,
    resourceName,
  };
}


export function pendingResourceAddition(
  option: ResourceAddOption,
  name: string,
  index: number,
): PendingResourceAddition {
  const identity = resourceAdditionIdentity(option.placement, name, index);
  return {
    ...identity,
    groupId: option.placement.groupId,
    resourcePlural: option.placement.resourcePlural,
    status: "syncing",
  };
}


export function pendingResourceRename(
  option: ResourceRenameOption,
  resourceId: string,
  newName: string,
): PendingResourceRename {
  const identity = resourceAdditionIdentity(option.placement, newName, 0);
  return {
    ...identity,
    groupId: option.placement.groupId,
    oldEditTargetId: option.editTargetId,
    oldId: resourceId,
    resourcePlural: option.placement.resourcePlural,
    status: "syncing",
  };
}
