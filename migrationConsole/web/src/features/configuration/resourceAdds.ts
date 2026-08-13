export interface ResourceAddPlacement {
  collectionPath: string;
  groupId: string;
  resourcePlural: string;
}


export interface ResourceAddOption {
  id: string;
  label: string;
  disabled: boolean;
  disabledReason?: string;
  placement: ResourceAddPlacement;
}


export interface ResourceAddController {
  options: ResourceAddOption[];
  busy: boolean;
  add: (optionId: string) => void;
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


const RESOURCE_ADD_PLACEMENTS: readonly ResourceAddPlacement[] = [
  {
    collectionPath: "sourceClusters",
    groupId: "group:Sources:Sources",
    resourcePlural: "sourceconfigs",
  },
  {
    collectionPath: "targetClusters",
    groupId: "group:Targets:Targets",
    resourcePlural: "targetconfigs",
  },
  {
    collectionPath: "snapshotMigrationConfigs",
    groupId: "group:Snapshot Migration:Backfill",
    resourcePlural: "snapshotmigrations",
  },
  {
    collectionPath: "traffic.kafkaClusters",
    groupId: "group:Live Traffic Migration:Buffer",
    resourcePlural: "kafkaclusters",
  },
  {
    collectionPath: "traffic.s3Sources",
    groupId: "group:Live Traffic Migration:Buffer",
    resourcePlural: "capturedtraffics",
  },
  {
    collectionPath: "traffic.proxies",
    groupId: "group:Live Traffic Migration:Capture",
    resourcePlural: "captureproxies",
  },
  {
    collectionPath: "traffic.replayers",
    groupId: "group:Live Traffic Migration:Replay",
    resourcePlural: "trafficreplays",
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
  const authoredName = name || `migration-${index + 1}`;
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
